package me.cortex.nvidium;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.*;
import me.cortex.nvidium.api0.NvidiumAPI;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.managers.RegionManager;
import me.cortex.nvidium.managers.RegionVisibilityTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.renderers.*;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.TickableManager;
import me.cortex.nvidium.util.UploadingBufferStream;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.joml.*;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.lang.Math;
import java.util.BitSet;
import java.util.List;

import static me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer.alignUp;
import static org.lwjgl.opengl.ARBDirectStateAccess.*;

public class RenderPipeline {
    public static final int GL_DRAW_INDIRECT_UNIFIED_NV = 0x8F40;
    public static final int GL_DRAW_INDIRECT_ADDRESS_NV = 0x8F41;

    private final RenderDevice device;
    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;
    private final SectionManager sectionManager;

    public final RegionVisibilityTracker regionVisibilityTracking;

    private PrimaryTerrainRasterizer terrainRasterizer;
    private RegionRasterizer regionRasterizer;
    private SectionRasterizer sectionRasterizer;
    private TemporalTerrainRasterizer temporalRasterizer;
    private TranslucentTerrainRasterizer translucencyTerrainRasterizer;
    private SortRegionSectionPhase regionSectionSorter;

    private final IDeviceMappedBuffer sceneUniform;
    private static final int SCENE_SIZE = (int) alignUp(4*4*4+4*4+4*4+4+4*4+4*4+8*8+3*4+3+4+8+8+(4*4*4), 2);

    private final IDeviceMappedBuffer regionVisibility;
    private final IDeviceMappedBuffer sectionVisibility;
    private final IDeviceMappedBuffer terrainCommandBuffer;
    private final IDeviceMappedBuffer translucencyCommandBuffer;
    private final IDeviceMappedBuffer regionSortingList;
    private final IDeviceMappedBuffer statisticsBuffer;
    private final IDeviceMappedBuffer transformationArray;
    private final IDeviceMappedBuffer originOffsetArray;

    private final BitSet regionVisibilityTracker;
    private final IntSet regionsToSort = new IntOpenHashSet();

    private static final class Statistics {
        public int frustumCount;
        public int regionCount;
        public int sectionCount;
        public int quadCount;
    }

    private final Statistics stats;
    private int prevRegionCount;
    private int frameId;
    private boolean compiledForFog = false;

    public RenderPipeline(RenderDevice device, UploadingBufferStream uploadStream, DownloadTaskStream downloadStream, SectionManager sectionManager) {
        this.device = device;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.sectionManager = sectionManager;
        this.compiledForFog = Nvidium.config.render_fog;

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();

        int maxRegions = sectionManager.getRegionManager().maxRegions();

        sceneUniform = device.createDeviceOnlyMappedBuffer(SCENE_SIZE + maxRegions*2L);
        regionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions);
        sectionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions * 256L);
        terrainCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L);
        translucencyCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions*8L);
        regionSortingList = device.createDeviceOnlyMappedBuffer(maxRegions*2L);
        this.transformationArray = device.createDeviceOnlyMappedBuffer(RegionManager.MAX_TRANSFORMATION_COUNT * (4*4*4));
        this.originOffsetArray = device.createDeviceOnlyMappedBuffer(RegionManager.MAX_TRANSFORMATION_COUNT * 8);

        regionVisibilityTracker = new BitSet(maxRegions);
        regionVisibilityTracking = new RegionVisibilityTracker(downloadStream, maxRegions);

        statisticsBuffer = device.createDeviceOnlyMappedBuffer(4*4);
        stats = new Statistics();

        // Initialize the transformationArray buffer to the identity affine transform
        {
            long ptr = this.uploadStream.upload(this.transformationArray, 0, RegionManager.MAX_TRANSFORMATION_COUNT * (4*4*4));
            var transform = new Matrix4f().identity();
            for (int i = 0; i < RegionManager.MAX_TRANSFORMATION_COUNT; i++) {
                transform.getToAddress(ptr);
                ptr += 4*4*4;
            }
        }
        // Clear the origin offset - TODO: Fix for 1.21.5
        // nglClearNamedBufferData(this.originOffsetArray.getId(), GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);
    }

    public void setTransformation(int id, Matrix4fc transform) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(this.transformationArray, id * (4*4*4), 4*4*4);
        transform.getToAddress(ptr);
    }

    public void setOrigin(int id, int x, int y, int z) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(this.originOffsetArray, id * 8, 8);
        long pos = 0;
        pos |= x&0x1ffffff;
        pos |= ((long)(z&0x1ffffff))<<25;
        pos |= ((long)(y&0x3fff))<<50;

        MemoryUtil.memPutLong(ptr, pos);
    }

    public void renderFrame(Viewport frustum, ChunkRenderMatrices crm, double px, double py, double pz) {
        // TODO: Fix OpenGL conflicts for 1.21.5 - temporarily disabled
        if (sectionManager.getRegionManager().regionCount() == 0) return;
        // Rendering logic temporarily disabled for compilation
    }

    void enqueueRegionSort(int regionId) {
        this.regionsToSort.add(regionId);
    }

    private void removeRegion(int id) {
        sectionManager.removeRegionById(id);
        regionVisibilityTracking.resetRegion(id);
    }

    public void removeARegion() {
        removeRegion(regionVisibilityTracking.findMostLikelyLeastSeenRegion(sectionManager.getRegionManager().maxRegionIndex()));
    }

    public void renderTranslucent() {
        // TODO: Fix OpenGL conflicts for 1.21.5 - temporarily disabled
        // Translucency rendering logic temporarily disabled for compilation
    }

    public void delete() {
        regionVisibilityTracking.delete();

        sceneUniform.delete();
        regionVisibility.delete();
        sectionVisibility.delete();
        terrainCommandBuffer.delete();
        translucencyCommandBuffer.delete();
        regionSortingList.delete();

        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();
        this.transformationArray.delete();
        this.originOffsetArray.delete();

        if (statisticsBuffer != null) {
            statisticsBuffer.delete();
        }
    }

    public void addDebugInfo(List<String> info) {
        if (Nvidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
            StringBuilder builder = new StringBuilder();
            builder.append("Statistics: ");
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.FRUSTUM.ordinal()) {
                builder.append("F: ").append(stats.frustumCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.REGIONS.ordinal()) {
                builder.append(", R: ").append(stats.regionCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.SECTIONS.ordinal()) {
                builder.append(", S: ").append(stats.sectionCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.QUADS.ordinal()) {
                builder.append(", Q: ").append(stats.quadCount);
            }
            info.addAll(List.of(builder.toString().split("\n")));
        }
    }

    public void reloadShaders() {
        this.compiledForFog = Nvidium.config.render_fog;
        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();
    }
}
