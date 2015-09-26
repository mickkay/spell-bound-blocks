package mickkay.sbb;

import static com.google.common.collect.Maps.newHashMap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockDirectional;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockPistonExtension;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.stats.Achievement;
import net.minecraft.stats.AchievementList;
import net.minecraft.stats.StatisticsFile;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.LongHashMap;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.BreakSpeed;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.BlockEvent.BreakEvent;
import net.minecraftforge.event.world.BlockEvent.PlaceEvent;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = SpellBoundBlocksMod.MODID, version = SpellBoundBlocksMod.VERSION,
    acceptableRemoteVersions = "*")
public class SpellBoundBlocksMod {
  private static final String CATEGORY_GENERAL = "general";
  private static final String PROP_BLOCK_PROTECTION_LEVEL = "BlockProtectionLevel";
  private static final String PROP_BLOCK_DESTRUCTION_COST = "BlockDestructionXpCostPerMissingLevel";
  private static final String PROP_ENABLE_BLOCK_DESTRUCTION_COST =
      "EnableBlockDestructionXpCostPerMissingLevel";

  public static final String MODID = "mickkay.SpellBoundBlocks";
  public static final String MOD_NAME = "SpellBoundBlocks-Mod";
  public static final String VERSION = "0.4.0";

  /**
   * The block protection level is defined either by the player's experience points or the number of
   * achievements.
   */
  public static enum BlockProtectionLevel {
    XP, ACHIEVEMENTS
  };

  private static final Logger logger = LogManager.getLogger(SpellBoundBlocksMod.class.getName());
  private BlockProtectionLevel blockProtectionLevel;
  private int blockDestructionCost;
  private boolean enableBlockDestructionCost;
  private final Map<World, File> protectionChunksDirMap = newHashMap();
  private final LongHashMap chunkMapping = new LongHashMap();
  private final EmptyBlockProtectionChunk emptyBlockProtectionChunk = new EmptyBlockProtectionChunk();

  public static SpellBoundBlocksMod INSTANCE;


  public SpellBoundBlocksMod() {
    INSTANCE = this;
  }

  @EventHandler
  public void preInit(FMLPreInitializationEvent event) {
    loadConfiguration(new Configuration(event.getSuggestedConfigurationFile()));
    // PistonExtension.registerModBlockPistonBase();
  }

  private void loadConfiguration(Configuration config) {
    logger.info("Loading Configuration for " + MOD_NAME);
    config.load();

    this.blockProtectionLevel = loadBlockProtectionLevel(config);
    this.enableBlockDestructionCost = loadEnableBlockDestructionCost(config);
    this.blockDestructionCost = loadBlockDestructionCost(config);

    config.save();
  }

  private BlockProtectionLevel loadBlockProtectionLevel(Configuration config) {
    Property prop =
        config.get(CATEGORY_GENERAL, PROP_BLOCK_PROTECTION_LEVEL,
            BlockProtectionLevel.ACHIEVEMENTS.name());
    prop.comment = "Valid values are " + Arrays.toString(BlockProtectionLevel.values());
    return BlockProtectionLevel.valueOf(prop.getString());
  }

  private boolean loadEnableBlockDestructionCost(Configuration config) {
    Property prop = config.get(CATEGORY_GENERAL, PROP_ENABLE_BLOCK_DESTRUCTION_COST, false);
    prop.comment = "Valid values are [true, false]";
    return prop.getBoolean();
  }

  private int loadBlockDestructionCost(Configuration config) {
    Property prop = config.get(CATEGORY_GENERAL, PROP_BLOCK_DESTRUCTION_COST, 1);
    prop.comment = "Valid values are [1, 2, ...]";
    int result = prop.getInt();
    if (result < 1) {
      throw new IllegalStateException(PROP_BLOCK_DESTRUCTION_COST
          + " must be greater than 0, but was " + result + "!");
    }
    return result;
  }

  public int getPlayerLevel(EntityPlayer player) {
    switch (this.blockProtectionLevel) {
      case XP:
        return player.experienceLevel;
      case ACHIEVEMENTS:
        return countAchivements(player);
      default:
        throw new IllegalStateException("Unknown protection level " + this.blockProtectionLevel);
    }
  }

  @EventHandler
  public void init(FMLInitializationEvent event) {
    logger.info("Initializing " + MOD_NAME);
    MinecraftForge.EVENT_BUS.register(this);
  }

  private File getProtectionDir(World world) {
    File result = protectionChunksDirMap.get(world);
    if (result == null) {
      File saveDir = DimensionManager.getCurrentSaveRootDirectory();
      String dimensionSaveFolder = world.provider.getSaveFolder();
      String postfix = dimensionSaveFolder==null?"":dimensionSaveFolder;
      result = new File(saveDir, "protection"+postfix);
      result.mkdirs();
      protectionChunksDirMap.put(world, result);
    }
    return result;
  }

  public int getBreakCosts(int levelDiff) {
    if (levelDiff <= 0) {
      return 0;
    }
    if (!enableBlockDestructionCost) {
      return Integer.MAX_VALUE;
    }
    return levelDiff * blockDestructionCost;
  }

  @SubscribeEvent
  public void onBreakSpeed(BreakSpeed event) {
    if (!isServer(event) || !shouldHandleEventFor(event.entityPlayer)) {
      return;
    }
    // log("onBreakSpeed", event.entityPlayer.worldObj);
    BlockProtectionChunk chunk = getBlockProtectionChunk(event.entityPlayer.getEntityWorld(), event.pos);
    int levelDiff = chunk.getProtectionLevelDifference(event.pos, event.entityPlayer);
    if (levelDiff > 0) {
      spawnProtectionParticles(event.entityPlayer.worldObj, event.pos, levelDiff * 10);
      int cost = getBreakCosts(levelDiff);
      if (cost > event.entityPlayer.experienceLevel) {
        // event.setCanceled(true);
        //event.newSpeed = 10f;
      }
    }
  }

  @SubscribeEvent
  public void onBreak(BreakEvent event) {
    if (!isServer(event) || !shouldHandleEventFor(event.getPlayer())) {
      return;
    }
    // log("onBreak", event.world);
    BlockProtectionChunk chunk = getBlockProtectionChunk(event.getPlayer().getEntityWorld(), event.pos);

    int levelDiff = chunk.getProtectionLevelDifference(event.pos, event.getPlayer());
    if (levelDiff > 0) {
      spawnProtectionParticles(event.world, event.pos, levelDiff * 10);
      int cost = getBreakCosts(levelDiff);
      if (cost > event.getPlayer().experienceLevel) {
        event.setCanceled(true);
        return;
      }
      event.getPlayer().addExperienceLevel(-cost);
    }
    chunk.setRemoved(event.pos);
  }

  @SubscribeEvent
  public void onPlace(PlaceEvent event) {
    if (!isServer(event)) {
      return;
    }
    // log("onPlace", event.world);
    BlockProtectionChunk chunk = getBlockProtectionChunk(event.world, event.pos);
    chunk.setPlacedByPlayer(event.player, event.pos);
  }


  /**
   * Called when chunk is loaded from disk.
   * 
   * @param event
   */
  @SubscribeEvent
  public void onLoadChunk(ChunkDataEvent.Load event) {
    if (!isServer(event)) {
      return;
    }
    // log("onLoadChunk",event.world);
    try {
      loadBlockProtectionChunk(event.world, event.getChunk().getChunkCoordIntPair());
    } catch (IOException e) {
      logger.error("Can't load chunk at (" + event.getChunk().xPosition + ","
          + event.getChunk().zPosition + ")", e);
    }
  }

  /**
   * Called when chunk is saved to disk.
   * 
   * @param event
   */
  @SubscribeEvent
  public void onSaveChunk(ChunkDataEvent.Save event) {
    if (!isServer(event)) {
      return;
    }
    // log("onSaveChunk",event.world);
    try {
      saveBlockProtectionChunk(event.world, event.getChunk().getChunkCoordIntPair());
    } catch (IOException e) {
      logger.error("Can't save chunk at (" + event.getChunk().xPosition + ","
          + event.getChunk().zPosition + ")", e);
    }
  }

  @SubscribeEvent
  public void onLoadChunk(ChunkEvent.Load event) {
    if (!isServer(event)) {
      return;
    }
    // log("onLoadChunk",event.world);
    try {
      if (!chunkIsLoaded(event.getChunk().getChunkCoordIntPair())) {
        loadBlockProtectionChunk(event.world, event.getChunk().getChunkCoordIntPair());
      }
    } catch (IOException e) {
      logger.error("Can't load chunk at (" + event.getChunk().xPosition + ","
          + event.getChunk().zPosition + ")", e);
    }
  }

  @SubscribeEvent
  public void onUnloadChunk(ChunkEvent.Unload event) {
    if (!isServer(event)) {
      return;
    }
    // log("onUnloadChunk",event.world);
    dropBuildersChunk(event.world, event.getChunk().getChunkCoordIntPair());
  }

  public BlockProtectionChunk getBlockProtectionChunk(World world, BlockPos pos) {
    long key = getKey(pos);
    BlockProtectionChunk result = (BlockProtectionChunk) chunkMapping.getValueByKey(key);
    if (result == null) {
      logger.warn("Empty chunk at " + pos);
      result = emptyBlockProtectionChunk;
    }
    return result;
  }

  private long getKey(BlockPos pos) {
    int chunkX = pos.getX() >> 4;
    int chunkZ = pos.getZ() >> 4;
    return ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ);
  }

  private boolean chunkIsLoaded(ChunkCoordIntPair pair) {
    long key = getKey(pair);
    BlockProtectionChunk chunk = (BlockProtectionChunk) chunkMapping.getValueByKey(key);
    return chunk != null;
  }

  private long getKey(ChunkCoordIntPair pair) {
    return ChunkCoordIntPair.chunkXZ2Int(pair.chunkXPos, pair.chunkZPos);
  }

  public void loadBlockProtectionChunk(World world, ChunkCoordIntPair pair) throws IOException {
    BlockProtectionChunk chunk = new BlockProtectionChunk();
    DataInputStream in = getChunkInputStream(world, pair);
    if (in != null) {
      chunk.loadFromStream(in);
    }
    chunkMapping.add(getKey(pair), chunk);
  }

  public void saveBlockProtectionChunk(World world, ChunkCoordIntPair pair) throws IOException {
    BlockProtectionChunk chunk = (BlockProtectionChunk) chunkMapping.getValueByKey(getKey(pair));
    if (chunk != null && chunk.isModified()) {
      DataOutputStream out = getChunkOutputStream(world, pair);
      chunk.saveToStream(out);
      chunk.setModified(false);
    }
  }

  private DataOutputStream getChunkOutputStream(World world, ChunkCoordIntPair pair)
      throws FileNotFoundException {
    File chunkFile = getChunkFile(world, pair);
    DataOutputStream result = new DataOutputStream(new FileOutputStream(chunkFile));
    return result;
  }

  private DataInputStream getChunkInputStream(World world, ChunkCoordIntPair pair)
      throws FileNotFoundException {
    File chunkFile = getChunkFile(world, pair);
    if (!chunkFile.exists()) {
      return null;
    }
    DataInputStream result = new DataInputStream(new FileInputStream(chunkFile));
    return result;
  }

  private File getChunkFile(World world, ChunkCoordIntPair pair) {
    File protDir = getProtectionDir(world);
    File chunkFile = new File(protDir, "p." + pair.chunkXPos + "." + pair.chunkZPos + ".bytes");
    return chunkFile;
  }

  public void dropBuildersChunk(World world, ChunkCoordIntPair key) {
    chunkMapping.remove(getKey(key));
  }

  private static int countAchivements(EntityPlayer player) {
    if (!(player instanceof EntityPlayerMP)) {
      return Integer.MAX_VALUE;
    } else {
      EntityPlayerMP mPlayer = (EntityPlayerMP) player;
      StatisticsFile stats = mPlayer.getStatFile();

      int result = 0;
      for (Object elem : AchievementList.achievementList) {
        if (elem instanceof Achievement) {
          Achievement achievement = (Achievement) elem;
          if (stats.hasAchievementUnlocked(achievement)) {
            result++;
          }
        }
      }
      return result;
    }
  }

  private void spawnProtectionParticles(World world, BlockPos pos, int numberOfParticles) {
    if (world instanceof WorldServer) {
      WorldServer worldServer = (WorldServer) world;

      EnumParticleTypes type = EnumParticleTypes.REDSTONE;
      boolean flag1 = false; // force

      double d6 = (double) pos.getX() + 0.5;
      double d0 = (double) pos.getY() + 0.5;
      double d1 = (double) pos.getZ() + 0.5;;

      double d2 = 0.3;
      double d3 = 0.3;
      double d4 = 0.3;
      double d5 = 0.5;
      int[] aint = new int[0];
      // log("spawnParticles "+numberOfParticles, world);
      worldServer.spawnParticle(type, flag1, d6, d0, d1, numberOfParticles, d2, d3, d4, d5, aint);
    }

  }

  private void x(EnumParticleTypes type, boolean flag1, double d6, double d0, double d1,
      int numberOfParticles, double d2, double d3, double d4, double d5, int[] aint) {
    // TODO Auto-generated method stub

  }

  private boolean isServer(WorldEvent event) {
    return isServer(event.world);
  }

  private boolean isServer(BlockEvent event) {
    return isServer(event.world);
  }

  private boolean isServer(EntityEvent event) {
    return isServer(event.entity.worldObj);
  }

  private boolean isServer(World world) {
    return !world.isRemote;
  }

  private void log(String string, World world) {
    String side = world.isRemote ? "Client" : "Server";
    logger.info(string + " " + side);
  }

  private boolean shouldHandleEventFor(EntityPlayer entityPlayer) {
    if (entityPlayer instanceof EntityPlayerMP) {
      EntityPlayerMP mPlayer = (EntityPlayerMP) entityPlayer;
      boolean result = mPlayer.theItemInWorldManager.getGameType().isSurvivalOrAdventure();
      return result;
    }
    return false;
  }

  public static class BlockProtectionChunk {
    private BlockProtectionLayer[] layers = new BlockProtectionLayer[16];
    private boolean modified;

    public BlockProtectionChunk() {
      for (int i = 0; i < layers.length; ++i) {
        layers[i] = new BlockProtectionLayer();
      }
    }

    public final void setModified(boolean b) {
      this.modified = b;
    }

    public boolean isModified() {
      return modified;
    }

    public void saveToStream(DataOutputStream out) throws IOException {
      for (int i = 0; i < layers.length; ++i) {
        layers[i].saveToStream(out);
      }
    }

    public void loadFromStream(DataInputStream in) throws IOException {
      for (int i = 0; i < layers.length; ++i) {
        layers[i].loadFromStream(in);
      }
    }

    public void setRemoved(BlockPos pos) {
      BlockProtectionLayer layer = this.layers[pos.getY() >> 4];
      int protectionLevel = 0;
      // logger.info("Set protection level at " + pos + " to #" +
      // protectionLevel);
      byte newLevel = (byte) protectionLevel;
      byte oldLevel = layer.setData(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, newLevel);
      if (oldLevel != newLevel) {
        setModified(true);
      }
    }

    public void setPlacedByPlayer(EntityPlayer player, BlockPos pos) {
      // int xpLevel = player.experienceLevel;
      // int achivements = countAchivements(player);
      int playerLevel = Math.min(255, INSTANCE.getPlayerLevel(player));
      BlockProtectionLayer storage = this.layers[pos.getY() >> 4];
      // logger.info("Set protection level at " + pos + " to #" +
      // playerLevel);
      byte newLevel = (byte) playerLevel;
      byte oldLevel = storage.setData(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, newLevel);
      if (oldLevel != newLevel) {
        setModified(true);
      }
    }

    public int getProtectionLevelDifference(BlockPos pos, EntityPlayer player) {
      byte protectionLevel = getProtectionLevel(pos, player.worldObj);
      int playerLevel = Math.min(255, INSTANCE.getPlayerLevel(player));
      int levelDiff = protectionLevel - playerLevel;
      return levelDiff;
    }

    private byte getProtectionLevel(BlockPos pos, World world) {
      IBlockState state = world.getBlockState(pos);
      if (state.getBlock() instanceof BlockDoor) {
        if (state.getValue(BlockDoor.HALF) == BlockDoor.EnumDoorHalf.UPPER) {
          return getProtectionLevel(pos.down(), world);
        }
      }
      if (state.getBlock() == Blocks.bed) {
        if (state.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD) {
          EnumFacing enumfacing = (EnumFacing) state.getValue(BlockDirectional.FACING);
          return getProtectionLevel(pos.offset(enumfacing.getOpposite()), world);
        }
      }
      if (state.getBlock() == Blocks.piston_head) {
        EnumFacing enumfacing = (EnumFacing) state.getValue(BlockPistonExtension.FACING);
        return getProtectionLevel(pos.offset(enumfacing.getOpposite()), world);
      }

      BlockProtectionLayer storage = this.layers[pos.getY() >> 4];
      byte protectionLevel = storage.getData(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
      return protectionLevel;
    }
  }

  public static class BlockProtectionLayer {
    private byte[] data = new byte[16 * 16 * 16];

    public byte setData(int x, int y, int z, byte value) {
      byte result = this.data[y << 8 | z << 4 | x];
      this.data[y << 8 | z << 4 | x] = value;
      logger.info("set " + x + "," + y + "," + z + "=" + value);
      return result;
    }

    public byte getData(int x, int y, int z) {
      byte result = this.data[y << 8 | z << 4 | x];
      logger.info("get " + x + "," + y + "," + z + "=" + result);
      return result;
    }

    public void saveToStream(DataOutputStream out) throws IOException {
      out.write(data);
    }

    public void loadFromStream(DataInputStream in) throws IOException {
      in.readFully(data);
    }
  }

  public static class EmptyBlockProtectionChunk extends BlockProtectionChunk {

    @Override
    public void setPlacedByPlayer(EntityPlayer player, BlockPos pos) {}

    @Override
    public int getProtectionLevelDifference(BlockPos pos, EntityPlayer player) {
      return Integer.MAX_VALUE;
    }

    @Override
    public boolean isModified() {
      return false;
    }

    @Override
    public void saveToStream(DataOutputStream out) throws IOException {}

    @Override
    public void loadFromStream(DataInputStream in) throws IOException {}
  }

}
