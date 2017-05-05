package li.cil.scannable.client.scanning;

import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import li.cil.scannable.api.prefab.AbstractScanResultProvider;
import li.cil.scannable.api.scanning.ScanResult;
import li.cil.scannable.common.Scannable;
import li.cil.scannable.common.config.Constants;
import li.cil.scannable.common.config.Settings;
import li.cil.scannable.common.init.Items;
import li.cil.scannable.common.item.ItemScannerModuleBlockConfigurable;
import li.cil.scannable.util.ItemStackUtils;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.VertexBuffer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.oredict.OreDictionary;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScanResultProviderBlock extends AbstractScanResultProvider {
    public static final ScanResultProviderBlock INSTANCE = new ScanResultProviderBlock();

    // --------------------------------------------------------------------- //

    private static final int DEFAULT_COLOR = 0x4466CC;
    private static final float BASE_ALPHA = 0.25f;
    private static final float STATE_SCANNED_ALPHA = 0.7f;

    private final Map<IBlockState, ItemStack> oresCommon = new HashMap<>();
    private final Map<IBlockState, ItemStack> oresRare = new HashMap<>();
    private final TObjectIntMap<IBlockState> oreColors = new TObjectIntHashMap<>();
    private boolean scanCommon, scanRare;
    @Nullable
    private IBlockState scanState;
    private final List<IProperty> stateComparator = new ArrayList<>();
    private float sqRadius, sqOreRadius;
    private int x, y, z;
    private BlockPos min, max;
    private int blocksPerTick;
    private Map<BlockPos, ScanResultOre> resultClusters = new HashMap<>();

    // --------------------------------------------------------------------- //

    private ScanResultProviderBlock() {
    }

    @SideOnly(Side.CLIENT)
    public void rebuildOreCache() {
        oreColors.clear();
        oresCommon.clear();
        oresRare.clear();

        buildOreCache();
    }

    // --------------------------------------------------------------------- //
    // ScanResultProvider

    @Override
    public int getEnergyCost(final EntityPlayer player, final ItemStack module) {
        if (Items.isModuleOreCommon(module)) {
            return Settings.getEnergyCostModuleOreCommon();
        }
        if (Items.isModuleOreRare(module)) {
            return Settings.getEnergyCostModuleOreRare();
        }
        if (Items.isModuleBlock(module)) {
            return Settings.getEnergyCostModuleBlock();
        }

        throw new IllegalArgumentException(String.format("Module not supported by this provider: %s", module));
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void initialize(final EntityPlayer player, final Collection<ItemStack> modules, final Vec3d center, final float radius, final int scanTicks) {
        super.initialize(player, modules, center, computeRadius(modules, radius), scanTicks);

        scanCommon = false;
        scanRare = false;
        scanState = null;
        stateComparator.clear();
        for (final ItemStack module : modules) {
            scanCommon |= Items.isModuleOreCommon(module);
            scanRare |= Items.isModuleOreRare(module);
            if (Items.isModuleBlock(module)) {
                scanState = ItemScannerModuleBlockConfigurable.getBlockState(module);
                if (scanState != null) {
                    // TODO Filter for configurable properties (configurable in the module).
                    for (final IProperty<?> property : scanState.getPropertyKeys()) {
                        if (Objects.equals(property.getName(), "variant") ||
                            Objects.equals(property.getName(), "type")) {
                            stateComparator.add(property);
                        }
                    }
                }
            }
        }

        sqRadius = this.radius * this.radius;
        sqOreRadius = radius * Constants.MODULE_ORE_RADIUS_MULTIPLIER;
        sqOreRadius *= sqOreRadius;
        min = new BlockPos(center).add(-this.radius, -this.radius, -this.radius);
        max = new BlockPos(center).add(this.radius, this.radius, this.radius);
        x = min.getX();
        y = min.getY() - 1; // -1 for initial moveNext.
        z = min.getZ();
        final BlockPos size = max.subtract(min);
        final int count = (size.getX() + 1) * (size.getY() + 1) * (size.getZ() + 1);
        blocksPerTick = MathHelper.ceil(count / (float) scanTicks);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void computeScanResults(final Consumer<ScanResult> callback) {
        final World world = player.getEntityWorld();
        final Set<Block> blacklist = Settings.getBlockBlacklistSet();
        for (int i = 0; i < blocksPerTick; i++) {
            if (!moveNext(world)) {
                return;
            }

            if (center.squareDistanceTo(x + 0.5, y + 0.5, z + 0.5) > sqRadius) {
                continue;
            }

            final BlockPos pos = new BlockPos(x, y, z);
            final IBlockState state = world.getBlockState(pos);

            if (blacklist.contains(state.getBlock())) {
                continue;
            }

            if (scanState != null) {
                if (stateMatches(state) && !tryAddToCluster(pos, state)) {
                    final ScanResultOre result = new ScanResultOre(state, pos, STATE_SCANNED_ALPHA);
                    callback.accept(result);
                    resultClusters.put(pos, result);
                    continue;
                }
            }

            if (!scanCommon && !scanRare) {
                continue;
            }

            if (center.squareDistanceTo(x + 0.5, y + 0.5, z + 0.5) > sqOreRadius) {
                continue;
            }

            final boolean matches = (scanCommon && oresCommon.containsKey(state)) || (scanRare && oresRare.containsKey(state));
            if (matches && !tryAddToCluster(pos, state)) {
                final ScanResultOre result = new ScanResultOre(state, pos);
                callback.accept(result);
                resultClusters.put(pos, result);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean stateMatches(final IBlockState state) {
        assert scanState != null;
        if (scanState.getBlock() != state.getBlock()) {
            return false;
        }

        if (stateComparator.isEmpty()) {
            return true;
        }

        for (final IProperty property : stateComparator) {
            if (!Objects.equals(state.getValue(property), scanState.getValue(property))) {
                return false;
            }
        }

        return true;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public boolean isValid(final ScanResult result) {
        return ((ScanResultOre) result).isRoot();
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void render(final Entity entity, final List<ScanResult> results, final float partialTicks) {
        final double posX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        final double posY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        final double posZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;

        final Vec3d lookVec = entity.getLook(partialTicks).normalize();
        final Vec3d playerEyes = entity.getPositionEyes(partialTicks);

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

        GlStateManager.pushMatrix();
        GlStateManager.translate(-posX, -posY, -posZ);

        final Tessellator tessellator = Tessellator.getInstance();
        final VertexBuffer buffer = tessellator.getBuffer();

        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        final float colorNormalizer = 1 / 255f;
        for (final ScanResult result : results) {
            final ScanResultOre resultOre = (ScanResultOre) result;
            final Vec3d toResult = resultOre.getPosition().subtract(playerEyes);
            final float lookDirDot = (float) lookVec.dotProduct(toResult.normalize());
            final float sqLookDirDot = lookDirDot * lookDirDot;
            final float sq2LookDirDot = sqLookDirDot * sqLookDirDot;
            final float focusScale = MathHelper.clamp(sq2LookDirDot * sq2LookDirDot + 0.005f, 0.5f, 1f);

            final int color;
            if (oreColors.containsKey(resultOre.state)) {
                color = oreColors.get(resultOre.state);
            } else {
                color = DEFAULT_COLOR;
            }

            final float r = ((color >> 16) & 0xFF) * colorNormalizer;
            final float g = ((color >> 8) & 0xFF) * colorNormalizer;
            final float b = (color & 0xFF) * colorNormalizer;
            final float a = Math.max(BASE_ALPHA, resultOre.getAlphaOverride()) * focusScale;

            drawCube(resultOre.bounds.minX, resultOre.bounds.minY, resultOre.bounds.minZ,
                     resultOre.bounds.maxX, resultOre.bounds.maxY, resultOre.bounds.maxZ,
                     r, g, b, a, buffer);
        }

        tessellator.draw();

        GlStateManager.popMatrix();

        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.enableLighting();
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void reset() {
        super.reset();
        x = y = z = 0;
        min = max = null;
        blocksPerTick = 0;
        resultClusters.clear();
    }

    // --------------------------------------------------------------------- //

    @SideOnly(Side.CLIENT)
    private static float computeRadius(final Collection<ItemStack> modules, final float radius) {
        boolean scanOres = false;
        boolean scanState = false;
        for (final ItemStack module : modules) {
            scanOres |= Items.isModuleOreCommon(module);
            scanOres |= Items.isModuleOreRare(module);
            scanState |= Items.isModuleBlock(module);
        }

        if (scanOres && scanState) {
            return radius * Math.max(Constants.MODULE_ORE_RADIUS_MULTIPLIER, Constants.MODULE_BLOCK_RADIUS_MULTIPLIER);
        } else if (scanOres) {
            return radius * Constants.MODULE_ORE_RADIUS_MULTIPLIER;
        } else {
            assert scanState;
            return radius * Constants.MODULE_BLOCK_RADIUS_MULTIPLIER;
        }
    }

    @SideOnly(Side.CLIENT)
    private boolean tryAddToCluster(final BlockPos pos, final IBlockState state) {
        final BlockPos min = pos.add(-2, -2, -2);
        final BlockPos max = pos.add(2, 2, 2);

        ScanResultOre root = null;
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    final BlockPos clusterPos = new BlockPos(x, y, z);
                    final ScanResultOre cluster = resultClusters.get(clusterPos);
                    if (cluster == null) {
                        continue;
                    }
                    if (!Objects.equals(state, cluster.state)) {
                        continue;
                    }

                    if (root == null) {
                        root = cluster.getRoot();
                        root.add(pos);
                        resultClusters.put(pos, root);
                    } else {
                        cluster.setRoot(root);
                    }
                }
            }
        }

        return root != null;
    }

    @SideOnly(Side.CLIENT)
    private boolean moveNext(final World world) {
        y++;
        if (y > max.getY() || y >= world.getHeight()) {
            y = min.getY();
            x++;
            if (x > max.getX()) {
                x = min.getX();
                z++;
                if (z > max.getZ()) {
                    blocksPerTick = 0;
                    return false;
                }
            }
        }
        return true;
    }

    @SideOnly(Side.CLIENT)
    private void buildOreCache() {
        Scannable.getLog().info("Building block state lookup table...");

        final long start = System.currentTimeMillis();

        final TObjectIntMap<String> oreColorsByOreName = buildOreColorTable();

        final Set<String> oreNamesBlacklist = new HashSet<>(Arrays.asList(Settings.getOreBlacklist()));
        final Set<String> oreNamesCommon = new HashSet<>(Arrays.asList(Settings.getCommonOres()));
        final Set<String> oreNamesRare = new HashSet<>(Arrays.asList(Settings.getRareOres()));

        final Pattern pattern = Pattern.compile("^ore[A-Z].*$");
        for (final Block block : ForgeRegistries.BLOCKS.getValues()) {
            for (final IBlockState state : block.getBlockState().getValidStates()) {
                final ItemStack stack = new ItemStack(block, 1, block.damageDropped(state));
                if (!ItemStackUtils.isEmpty(stack)) {
                    final int[] ids = OreDictionary.getOreIDs(stack);
                    boolean isRare = false;
                    boolean isCommon = false;
                    for (final int id : ids) {
                        final String name = OreDictionary.getOreName(id);
                        if (oreNamesBlacklist.contains(name)) {
                            isRare = false;
                            isCommon = false;
                            break;
                        }

                        if (oreNamesCommon.contains(name)) {
                            isCommon = true;
                        } else if (oreNamesRare.contains(name) || pattern.matcher(name).matches()) {
                            isRare = true;
                        } else {
                            continue;
                        }

                        if (oreColorsByOreName.containsKey(name)) {
                            oreColors.put(state, oreColorsByOreName.get(name));
                        }
                    }

                    if (isCommon) {
                        oresCommon.put(state, stack);
                    } else if (isRare) {
                        oresRare.put(state, stack);
                    }
                }
            }
        }

        Scannable.getLog().info("Built    block state lookup table in {} ms.", System.currentTimeMillis() - start);
    }

    @SideOnly(Side.CLIENT)
    private static TObjectIntMap<String> buildOreColorTable() {
        final TObjectIntMap<String> oreColorsByOreName = new TObjectIntHashMap<>();

        final Pattern pattern = Pattern.compile("^(?<name>[^\\s=]+)\\s*=\\s*0x(?<color>[a-fA-F0-9]+)$");
        for (final String oreColor : Settings.getOreColors()) {
            final Matcher matcher = pattern.matcher(oreColor.trim());
            if (!matcher.matches()) {
                Scannable.getLog().warn("Illegal ore color entry in settings: '{}'", oreColor.trim());
                continue;
            }

            final String name = matcher.group("name");
            final int color = Integer.parseInt(matcher.group("color"), 16);

            oreColorsByOreName.put(name, color);
        }

        return oreColorsByOreName;
    }

    // --------------------------------------------------------------------- //

    private class ScanResultOre implements ScanResult {
        private final IBlockState state;
        private AxisAlignedBB bounds;
        @Nullable
        private ScanResultOre parent;
        private float alphaOverride;

        ScanResultOre(final IBlockState state, final BlockPos pos, final float alphaOverride) {
            bounds = new AxisAlignedBB(pos);
            this.state = state;
            this.alphaOverride = alphaOverride;
        }

        ScanResultOre(final IBlockState state, final BlockPos pos) {
            this(state, pos, 0f);
        }

        float getAlphaOverride() {
            return alphaOverride;
        }

        boolean isRoot() {
            return parent == null;
        }

        ScanResultOre getRoot() {
            if (parent != null) {
                return parent.getRoot();
            }
            return this;
        }

        void setRoot(final ScanResultOre root) {
            if (parent != null) {
                parent.setRoot(root);
                return;
            }
            if (root == this) {
                return;
            }

            root.bounds = root.bounds.union(bounds);
            parent = root;
        }

        void add(final BlockPos pos) {
            assert parent == null : "Trying to add to non-root node.";
            bounds = bounds.union(new AxisAlignedBB(pos));
        }

        // --------------------------------------------------------------------- //
        // ScanResult

        @Nullable
        @Override
        public AxisAlignedBB getRenderBounds() {
            return bounds;
        }

        @Override
        public Vec3d getPosition() {
            return bounds.getCenter();
        }
    }
}