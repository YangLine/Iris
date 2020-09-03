package com.volmit.iris.gen;

import java.io.IOException;
import java.util.List;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;

import com.volmit.iris.gen.atomics.AtomicSliver;
import com.volmit.iris.gen.atomics.AtomicSliverMap;
import com.volmit.iris.gen.atomics.AtomicWorldData;
import com.volmit.iris.gen.atomics.MasterLock;
import com.volmit.iris.gen.layer.GenLayerText;
import com.volmit.iris.gen.layer.GenLayerUpdate;
import com.volmit.iris.object.IrisBiome;
import com.volmit.iris.object.IrisBiomeMutation;
import com.volmit.iris.object.IrisObjectPlacement;
import com.volmit.iris.object.IrisRegion;
import com.volmit.iris.object.IrisStructurePlacement;
import com.volmit.iris.object.IrisTextPlacement;
import com.volmit.iris.util.BiomeMap;
import com.volmit.iris.util.CaveResult;
import com.volmit.iris.util.ChunkPosition;
import com.volmit.iris.util.HeightMap;
import com.volmit.iris.util.IObjectPlacer;
import com.volmit.iris.util.IrisLock;
import com.volmit.iris.util.IrisStructureResult;
import com.volmit.iris.util.KList;
import com.volmit.iris.util.KMap;
import com.volmit.iris.util.PrecisionStopwatch;
import com.volmit.iris.util.RNG;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public abstract class ParallaxChunkGenerator extends TerrainChunkGenerator implements IObjectPlacer
{
	private short cacheID = 0;
	protected KMap<ChunkPosition, AtomicSliver> sliverCache;
	protected AtomicWorldData parallaxMap;
	private MasterLock masterLock;
	private IrisLock flock = new IrisLock("ParallaxLock");
	private IrisLock lock = new IrisLock("ParallaxLock");
	private GenLayerUpdate glUpdate;
	private GenLayerText glText;
	private int sliverBuffer;

	public ParallaxChunkGenerator(String dimensionName, int threads)
	{
		super(dimensionName, threads);
		sliverCache = new KMap<>();
		sliverBuffer = 0;
		masterLock = new MasterLock();
	}

	public void onInit(World world, RNG rng)
	{
		super.onInit(world, rng);
		parallaxMap = new AtomicWorldData(world);
		glText = new GenLayerText(this, rng.nextParallelRNG(32485));
	}

	protected KMap<ChunkPosition, AtomicSliver> getSliverCache()
	{
		return sliverCache;
	}

	protected void onClose()
	{
		super.onClose();

		try
		{
			parallaxMap.unloadAll(true);
		}

		catch(IOException e)
		{
			e.printStackTrace();
		}
	}

	@Override
	public int getHighest(int x, int z)
	{
		return getHighest(x, z, false);
	}

	@Override
	public void onHotload()
	{
		getData().preferFolder(getDimension().getLoadFile().getParentFile().getParentFile().getName());
		super.onHotload();
		cacheID = RNG.r.simax();
	}

	@Override
	public int getHighest(int x, int z, boolean ignoreFluid)
	{
		int h = (int) Math.round(ignoreFluid ? getTerrainHeight(x, z) : getTerrainWaterHeight(x, z));

		if(getDimension().isCarving() && h >= getDimension().getCarvingMin())
		{
			while(getGlCarve().isCarved(x, h, z))
			{
				h--;
			}

			return h;
		}

		return h;
	}

	@Override
	public void set(int x, int y, int z, BlockData d)
	{
		getParallaxSliver(x, z).set(y, d);
	}

	@Override
	public BlockData get(int x, int y, int z)
	{
		BlockData b = sampleSliver(x, z).getBlock().get(y);
		return b == null ? AIR : b;
	}

	@Override
	public boolean isSolid(int x, int y, int z)
	{
		return get(x, y, z).getMaterial().isSolid();
	}

	public AtomicSliver getParallaxSliver(int wx, int wz)
	{
		getMasterLock().lock("gpc");
		getMasterLock().lock((wx >> 4) + "." + (wz >> 4));
		AtomicSliverMap map = getParallaxChunk(wx >> 4, wz >> 4);
		getMasterLock().unlock("gpc");
		AtomicSliver sliver = map.getSliver(wx & 15, wz & 15);
		getMasterLock().unlock((wx >> 4) + "." + (wz >> 4));

		return sliver;
	}

	public boolean isParallaxGenerated(int x, int z)
	{
		return getParallaxChunk(x, z).isParallaxGenerated();
	}

	public boolean isWorldGenerated(int x, int z)
	{
		return getParallaxChunk(x, z).isWorldGenerated();
	}

	public AtomicWorldData getParallaxMap()
	{
		return parallaxMap;
	}

	public AtomicSliverMap getParallaxChunk(int x, int z)
	{
		try
		{
			return getParallaxMap().loadChunk(x, z);
		}

		catch(IOException e)
		{
			fail(e);
		}

		return new AtomicSliverMap();
	}

	@Override
	public List<BlockPopulator> getDefaultPopulators(World world)
	{
		List<BlockPopulator> g = super.getDefaultPopulators(world);

		if(glUpdate == null)
		{
			glUpdate = new GenLayerUpdate(this, world);
		}

		g.add(glUpdate);
		return g;
	}

	@Override
	protected void onPostGenerate(RNG random, int x, int z, ChunkData data, BiomeGrid grid, HeightMap height, BiomeMap biomeMap, AtomicSliverMap map)
	{
		if(getSliverCache().size() > 20000)
		{
			getSliverCache().clear();
		}

		super.onPostGenerate(random, x, z, data, grid, height, biomeMap, map);
		PrecisionStopwatch p = PrecisionStopwatch.start();

		if(getDimension().isPlaceObjects())
		{
			onGenerateParallax(random, x, z);
			getParallaxChunk(x, z).inject(data);
		}

		getParallaxChunk(x, z).injectUpdates(map);
		setSliverBuffer(getSliverCache().size());
		getParallaxChunk(x, z).setWorldGenerated(true);
		getMasterLock().clear();

		p.end();
		getMetrics().getParallax().put(p.getMilliseconds());
		super.onPostParallaxPostGenerate(random, x, z, data, grid, height, biomeMap, map);
		getParallaxMap().clean(ticks);
		getData().getObjectLoader().clean();
	}

	public IrisStructureResult getStructure(int x, int y, int z)
	{
		return getParallaxChunk(x >> 4, z >> 4).getStructure(this, y);
	}

	protected void onGenerateParallax(RNG randomx, int x, int z)
	{
		String key = "par." + x + "." + z;
		ChunkPosition rad = getDimension().getParallaxSize(this);

		for(int ii = x - (rad.getX() / 2); ii <= x + (rad.getX() / 2); ii++)
		{
			int i = ii;

			for(int jj = z - (rad.getZ() / 2); jj <= z + (rad.getZ() / 2); jj++)
			{
				int j = jj;

				RNG random = getMasterRandom().nextParallelRNG(i).nextParallelRNG(j);

				if(isParallaxGenerated(ii, jj))
				{
					continue;
				}

				if(isWorldGenerated(ii, jj))
				{
					continue;
				}

				getAccelerant().queue(key, () ->
				{
					IrisBiome b = sampleTrueBiome((i * 16) + 7, (j * 16) + 7);
					RNG ro = getMasterRandom().nextParallelRNG(496888 + i + j);
					int g = 1;

					searching: for(IrisBiomeMutation k : getDimension().getMutations())
					{
						for(int l = 0; l < k.getChecks(); l++)
						{
							IrisBiome sa = sampleTrueBiome(((i * 16) + ro.nextInt(16)) + ro.i(-k.getRadius(), k.getRadius()), ((j * 16) + ro.nextInt(16)) + ro.i(-k.getRadius(), k.getRadius()));
							IrisBiome sb = sampleTrueBiome(((i * 16) + ro.nextInt(16)) + ro.i(-k.getRadius(), k.getRadius()), ((j * 16) + ro.nextInt(16)) + ro.i(-k.getRadius(), k.getRadius()));

							if(sa.getLoadKey().equals(sb.getLoadKey()))
							{
								continue;
							}

							if(k.getRealSideA(this).contains(sa.getLoadKey()) && k.getRealSideB(this).contains(sb.getLoadKey()))
							{
								for(IrisObjectPlacement m : k.getObjects())
								{
									int gg = g++;
									placeObject(m, i, j, random.nextParallelRNG((34 * ((i * 30) + (j * 30) + gg) * i * j) + i - j + 1569962));
								}

								continue searching;
							}
						}
					}

					IrisRegion r = sampleRegion((i * 16) + 7, (j * 16) + 7);

					for(IrisTextPlacement k : getDimension().getText())
					{
						k.place(this, random.nextParallelRNG(-7228 + (34 * ((i * 30) + (j * 30)) * i * j) + i - j + 1569962), i, j);
					}

					for(IrisTextPlacement k : r.getText())
					{
						k.place(this, random.nextParallelRNG(-4228 + -7228 + (34 * ((i * 30) + (j * 30)) * i * j) + i - j + 1569962), i, j);
					}

					for(IrisTextPlacement k : b.getText())
					{
						k.place(this, random.nextParallelRNG(-22228 + -4228 + -7228 + (34 * ((i * 30) + (j * 30)) * i * j) + i - j + 1569962), i, j);
					}

					for(IrisStructurePlacement k : r.getStructures())
					{
						k.place(this, random.nextParallelRNG(2228), i, j);
					}

					for(IrisStructurePlacement k : b.getStructures())
					{
						k.place(this, random.nextParallelRNG(-22228), i, j);
					}

					for(IrisObjectPlacement k : b.getObjects())
					{
						int gg = g++;
						placeObject(k, i, j, random.nextParallelRNG((34 * ((i * 30) + (j * 30) + gg) * i * j) + i - j + 3569222));
					}

					if(getDimension().isCaves())
					{
						int bx = (i * 16) + ro.nextInt(16);
						int bz = (j * 16) + ro.nextInt(16);

						IrisBiome biome = sampleCaveBiome(bx, bz);

						if(biome == null)
						{
							return;
						}

						if(biome.getObjects().isEmpty())
						{
							return;
						}

						for(IrisObjectPlacement k : biome.getObjects())
						{
							int gg = g++;
							placeCaveObject(k, i, j, random.nextParallelRNG((34 * ((i * 30) + (j * 30) + gg) * i * j) + i - j + 1869322));
						}
					}
				});

				getParallaxChunk(ii, jj).setParallaxGenerated(true);
			}
		}

		getAccelerant().waitFor(key);
	}

	public void placeObject(IrisObjectPlacement o, int x, int z, RNG rng)
	{
		for(int i = 0; i < o.getTriesForChunk(rng); i++)
		{
			rng = rng.nextParallelRNG((i * 3 + 8) - 23040);
			o.getSchematic(this, rng).place((x * 16) + rng.nextInt(16), (z * 16) + rng.nextInt(16), this, o, rng);
		}
	}

	public void placeCaveObject(IrisObjectPlacement o, int x, int z, RNG rng)
	{
		for(int i = 0; i < o.getTriesForChunk(rng); i++)
		{
			rng = rng.nextParallelRNG((i * 3 + 8) - 23040);
			int xx = (x * 16) + rng.nextInt(16);
			int zz = (z * 16) + rng.nextInt(16);
			KList<CaveResult> res = getCaves(xx, zz);

			if(res.isEmpty())
			{
				continue;
			}

			o.getSchematic(this, rng).place(xx, res.get(rng.nextParallelRNG(29345 * (i + 234)).nextInt(res.size())).getFloor() + 2, zz, this, o, rng);
		}
	}

	public AtomicSliver sampleSliver(int x, int z)
	{
		ChunkPosition key = new ChunkPosition(x, z);

		if(getSliverCache().containsKey(key))
		{
			return getSliverCache().get(key);
		}

		AtomicSliver s = new AtomicSliver(x & 15, z & 15);
		onGenerateColumn(x >> 4, z >> 4, x, z, x & 15, z & 15, s, null, true);
		getSliverCache().put(key, s);

		return s;
	}

	@Override
	public boolean isPreventingDecay()
	{
		return getDimension().isPreventLeafDecay();
	}
}
