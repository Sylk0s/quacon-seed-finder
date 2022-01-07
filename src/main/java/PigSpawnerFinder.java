import com.google.common.collect.ImmutableSet;
import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.rand.seed.RegionSeed;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.util.pos.RPos;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.SwampHut;
import com.seedfinding.mcmath.component.vector.QVector;
import com.seedfinding.mcreversal.Lattice2D;
import kaptainwutax.biomeutils.Biome;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;
import kaptainwutax.biomeutils.terrain.OverworldChunkGenerator;
import kaptainwutax.seedutils.lcg.LCG;
import kaptainwutax.seedutils.mc.ChunkRand;
import kaptainwutax.seedutils.mc.MCVersion;
import reverser.CarverReverser;
import spiderfinder.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PigSpawnerFinder {
    private static final ImmutableSet<Integer> BADLANDS = ImmutableSet.of(Biome.BADLANDS.getId(), Biome.BADLANDS_PLATEAU.getId(), Biome.ERODED_BADLANDS.getId(), Biome.MODIFIED_BADLANDS_PLATEAU.getId(), Biome.MODIFIED_WOODED_BADLANDS_PLATEAU.getId(), Biome.WOODED_BADLANDS_PLATEAU.getId());

    private static final long[] REGION_SEEDS = getQuadRegionSeeds();
    public static final com.seedfinding.mccore.version.MCVersion VERSION = com.seedfinding.mccore.version.MCVersion.v1_17;
    public static final SwampHut SWAMP_HUT= new SwampHut(VERSION);
    private static final Lattice2D REGION_LATTICE = new Lattice2D(RegionSeed.A, RegionSeed.B, 1L << 48);

    public static final RegionStructure<?, ?> CURRENT_STRUCTURE = SWAMP_HUT;
    public static final int WORLD_SIZE=30_000_000;

    private static void checkForQWH(long worldSeed) {
        int regionSize=CURRENT_STRUCTURE.getSpacing()*16;
        // int numberRegions=WORLD_SIZE/regionSize; // uncomment for whole world check
        int numberRegions = 10; // change for a closer check
        BiomeSource biomeSource = BiomeSource.of(CURRENT_STRUCTURE.getValidDimension(),VERSION, worldSeed);
        for (long regionSeed:REGION_SEEDS){
            for(QVector solution : REGION_LATTICE.findSolutionsInBox(regionSeed - worldSeed - CURRENT_STRUCTURE.getSalt(), -numberRegions, -numberRegions, numberRegions, numberRegions)) {
                int regX=solution.get(0).intValue();
                int regZ=solution.get(1).intValue();
                if(!checkBiomes(biomeSource, regX,regZ, CURRENT_STRUCTURE)) continue;
                System.out.println(new RPos(regX,regZ,regionSize).toBlockPos());
            }
        }
    }

    private static long[] getQuadRegionSeeds() {
        InputStream stream = PigSpawnerFinder.class.getResourceAsStream("/regionSeeds.txt");
        return new BufferedReader(new InputStreamReader(Objects.requireNonNull(stream)))
                .lines().mapToLong(Long::parseLong).toArray();
    }

    private static boolean checkBiomes(BiomeSource source, int regX, int regZ, RegionStructure<?,?> structure) {
        if(checkStructure(source, regX,regZ, structure)) return false;
        if(checkStructure(source,regX-1, regZ, structure)) return false;
        if(checkStructure(source, regX, regZ-1, structure)) return false;
        if(checkStructure(source, regX-1, regZ-1, structure)) return false;
        return true;
    }

    private static boolean checkStructure(BiomeSource source, int regX, int regZ, RegionStructure<?,?> structure) {
        CPos chunk = structure.getInRegion(source.getWorldSeed(), regX, regZ, new com.seedfinding.mccore.rand.ChunkRand());
        return !structure.canSpawn(chunk.getX(), chunk.getZ(), source);
    }

    private static void processStructureSeed(long structureSeed, int centerChunkX, int centerChunkZ, int spawnerX, int spawnerY, int spawnerZ) {
        OverworldBiomeSource biomeSource;
        OverworldChunkGenerator chunkGenerator;

        int spawnerChunkX = spawnerX >> 4;
        int spawnerChunkZ = spawnerZ >> 4;

        for(long top = 0; top < (1 << 16); top++) {
            long worldSeed = structureSeed | (top << 48);

            //Check biomes
            biomeSource = new OverworldBiomeSource(MCVersion.v1_14, worldSeed);
            if(!BADLANDS.contains(biomeSource.getBiomeForNoiseGen((centerChunkX << 2) + 2, 0, (centerChunkZ << 2) + 2).getId())) continue;
            if(biomeSource.getBiomeForNoiseGen((spawnerChunkX << 2) + 2, 0, (spawnerChunkZ << 2) + 2) != Biome.BEACH) continue;
//            System.out.println("Good biomes: " + worldSeed);

            //Check depth above spawner
            chunkGenerator = new OverworldChunkGenerator(biomeSource);
            int height = chunkGenerator.getHeightInGround(spawnerX, spawnerZ);
            int depth = height - spawnerY;
//            System.out.println(depth);
            if(depth < 3 || depth > 6) continue;
//            System.out.println("Good central height: " + worldSeed);

            //Check depth nearby to avoid water
            boolean good = true;
            for(int ox = -1; ox <= 1 && good; ox++) {
                for(int oz = -1; oz <= 1; oz++) {
                    height = chunkGenerator.getHeightInGround(spawnerX + ox * 4, spawnerZ + oz * 4);
                    depth = height - spawnerY;
                    if(depth < 3 && height < 62) {
                        good = false;
                        break;
                    }
                }
            }
            if(!good) continue;

            System.out.println("-=-=-=-=-=-=-=-=-=-=-");
            System.out.println("Good nearby height: " + worldSeed + " " + spawnerX + " " + spawnerY + " " + spawnerZ);

            checkForQWH(worldSeed);

        }
    }

    private static void processCarverSeed(long carverSeed, int spawnerCarverX, int spawnerY, int spawnerCarverZ, Direction direction, int length) {
        ChunkRand rand = new ChunkRand();
        ArrayList<Long> structureSeeds = new ArrayList<>();

        int radius = 32768 >> 4;
        int increment = 1 << 5;

        int m = length * 5;
        LCG skipCeiling = LCG.JAVA.combine(m * 3);
        LCG skipCobwebs = LCG.JAVA.combine(m * 3 * 2);
        LCG skip2 = LCG.JAVA.combine(2);
        LCG skip8 = LCG.JAVA.combine(8);
        LCG skip3 = LCG.JAVA.combine(3);


        int spawnerChunkCarverX = spawnerCarverX >> 4;
        int spawnerChunkCarverZ = spawnerCarverZ >> 4;

        int spawnerOffsetX = spawnerCarverX - (spawnerChunkCarverX << 4);
        int spawnerOffsetZ = spawnerCarverZ - (spawnerChunkCarverZ << 4);
        int spawnerOffset = direction.axis == Direction.Axis.X ? spawnerOffsetX : spawnerOffsetZ;

        for(int centerChunkX = -radius; centerChunkX <= radius; centerChunkX += increment) {
            for(int centerChunkZ = -radius; centerChunkZ <= radius; centerChunkZ += increment) {
                int spawnerChunkRealX = centerChunkX + spawnerChunkCarverX;
                int spawnerChunkRealZ = centerChunkZ + spawnerChunkCarverZ;

                structureSeeds.clear();
                CarverReverser.reverseCarverSeed(carverSeed, centerChunkX, centerChunkZ, structureSeeds);
                for(long structureSeed : structureSeeds) {
                    //Check for buried treasure
                    rand.setRegionSeed(structureSeed, spawnerChunkRealX, spawnerChunkRealZ, 10387320, MCVersion.v1_16);
                    if(rand.nextFloat() >= 0.01F) continue;

//                    System.out.println("Good treasure: " + structureSeed);

                    //Check for cobwebs and spawner position
                    //The spawner piece is the first corridor piece generated in this chunk so there are no random calls before it
                    rand.setDecoratorSeed(structureSeed, spawnerChunkRealX << 4, spawnerChunkRealZ << 4, 0, 3, MCVersion.v1_16);

                    //   skip ceiling air blocks
                    rand.advance(skipCeiling);
                    //   skip cobwebs
                    rand.advance(skipCobwebs);
                    //   skip supports
                    if(rand.nextInt(4) != 0) {
                        rand.advance(skip2);
                    }
                    //   skip additional cobwebs
                    rand.advance(skip8);
                    //   skip chests
                    for(int i = 0; i < 2; i++) {
                        if(rand.nextInt(100) == 0) {
                            rand.advance(skip3);
                        }
                    }

                    int spawnerShift = rand.nextInt(3) - 1;
                    int spawnerShiftReal = direction == Direction.NORTH || direction == Direction.WEST ? -spawnerShift : spawnerShift;
//                    System.out.println(spawnerOffset + spawnerShift);
                    if(spawnerOffset + spawnerShiftReal != 9) continue;

                    //Check for no cobwebs near the spawner
                    rand.setDecoratorSeed(structureSeed, spawnerChunkRealX << 4, spawnerChunkRealZ << 4, 0, 3, MCVersion.v1_16);
                    rand.advance(skipCeiling);

                    boolean hasCobwebsNearby = false;
                    for(int y = 0; y < 2 && !hasCobwebsNearby; y++) {
                        for(int x = 0; x < 3 && !hasCobwebsNearby; x++) {
                            for(int z = 0; z < m; z++) {
                                boolean hasCobweb = rand.nextFloat() < 0.6F;
                                if(hasCobweb) {
                                    if(
                                            (y == 1 && x == 1 && z == (2 + spawnerShift)) ||
                                            (y == 0 && x == 2 && z == (2 + spawnerShift)) ||
                                            (y == 0 && x == 0 && z == (2 + spawnerShift)) ||
                                            (y == 0 && x == 1 && z == (2 + spawnerShift + 1)) ||
                                            (y == 0 && x == 1 && z == (2 + spawnerShift - 1))
                                    ) {
                                        hasCobwebsNearby = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    if(hasCobwebsNearby) continue;

                    int spawnerRealX = (spawnerChunkRealX << 4) + 9;
                    int spawnerRealZ = (spawnerChunkRealZ << 4) + 9;

                    //System.out.println("Structure: " + structureSeed + " Center: " + centerChunkX + " " + centerChunkZ + " Spawner: " + spawnerRealX + " " + spawnerY + " " + spawnerRealZ);
                    processStructureSeed(structureSeed, centerChunkX, centerChunkZ, spawnerRealX, spawnerY, spawnerRealZ);
                }
            }
        }
    }

    private static void findCarvers(long carverSeed) {
        ArrayList<Spawner> spawners = new ArrayList<>();

        ArrayList<StructurePiece> pieces = MineshaftGenerator.generateForChunk(carverSeed, 0, 0, true, spawners);
        for(Spawner spawner : spawners) {
                //Check spawner height
            if(spawner.y < 58 || spawner.y > 59) continue;

                //Check if it can be at 9 9 but not near supports
            int offsetChunkX = spawner.x >> 4;
            int offsetChunkZ = spawner.z >> 4;
            int spawnerOffsetX = spawner.x - (offsetChunkX << 4);
            int spawnerOffsetZ = spawner.z - (offsetChunkZ << 4);
            if(!(spawner.direction.axis == Direction.Axis.X && spawnerOffsetZ == 9 && (spawnerOffsetX == 8 || spawnerOffsetX == 10)) && !(spawner.direction.axis == Direction.Axis.Z && spawnerOffsetX == 9 && (spawnerOffsetZ == 8 || spawnerOffsetZ == 10))) continue;

                //Check if it isn't too close to mesa
            if(Math.abs(offsetChunkX) < 2 && Math.abs(offsetChunkZ) < 2) continue;

                //Check if there are no other corridors in the same chunk generated before the one with the spawner (meaning no random calls before our corridor)
            int piecesBeforeSpawner = 0;
            BlockBox spawnerBox = new BlockBox(spawner.x, spawner.y, spawner.z, spawner.x, spawner.y, spawner.z);
            BlockBox chunk = new BlockBox(offsetChunkX << 4, 0, offsetChunkZ << 4, (offsetChunkX << 4) + 15, 255, (offsetChunkZ << 4) + 15);
            for(StructurePiece piece : pieces) {
                if(piece.boundingBox.intersects(chunk)) {
                    if(piece.boundingBox.intersects(spawnerBox)) break;
                    else if (piece instanceof MineshaftGenerator.MineshaftCorridor) {
                        piecesBeforeSpawner = 1;
                        break;
                    }
                }
            }

            if(piecesBeforeSpawner != 0) continue;

            processCarverSeed(carverSeed, spawner.x, spawner.y, spawner.z, spawner.direction, spawner.length);
        }
        spawners.clear();
    }

    static long currentStep = 1796831724L;
    static final int BATCH_SIZE = 100000000; // this seems to be the most 0s i can put without crashing stuff


    public static List<Long> generateBatch() {
        List<Long> batch = new ArrayList<>();
        for(long i = 0; i < BATCH_SIZE; i++) {
            batch.add(currentStep+i);
        }
        currentStep += BATCH_SIZE;
        return batch;
    }

    public static void main(String[] args) {
        while(currentStep <= (1L<<48)) {
            generateBatch().parallelStream().forEach(PigSpawnerFinder::findCarvers);
        }
        System.out.println("This is all the seeds possible. You have reached the end. Congratulations!");
    }
}