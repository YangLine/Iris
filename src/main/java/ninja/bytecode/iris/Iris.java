package ninja.bytecode.iris;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import ninja.bytecode.iris.generator.IrisGenerator;
import ninja.bytecode.iris.object.IrisBiome;
import ninja.bytecode.iris.object.IrisDimension;
import ninja.bytecode.iris.object.IrisObject;
import ninja.bytecode.iris.util.BiomeResult;
import ninja.bytecode.iris.util.BoardManager;
import ninja.bytecode.iris.util.BoardProvider;
import ninja.bytecode.iris.util.BoardSettings;
import ninja.bytecode.iris.util.CNG;
import ninja.bytecode.iris.util.GroupedExecutor;
import ninja.bytecode.iris.util.IO;
import ninja.bytecode.iris.util.ScoreDirection;
import ninja.bytecode.iris.wand.WandController;
import ninja.bytecode.shuriken.collections.KList;
import ninja.bytecode.shuriken.execution.J;
import ninja.bytecode.shuriken.format.Form;
import ninja.bytecode.shuriken.math.RollingSequence;
import ninja.bytecode.shuriken.reaction.O;

public class Iris extends JavaPlugin implements BoardProvider
{
	public static KList<GroupedExecutor> executors = new KList<>();
	public static Iris instance;
	public static IrisDataManager data;
	public static IrisHotloadManager hotloader;
	public static WandController wand;
	private static String last = "";
	private BoardManager manager;
	private RollingSequence hits = new RollingSequence(20);

	public Iris()
	{
		IO.delete(new File("iris"));
	}

	public void onEnable()
	{
		instance = this;
		hotloader = new IrisHotloadManager();
		data = new IrisDataManager(getDataFolder());
		wand = new WandController();
		manager = new BoardManager(this, BoardSettings.builder().boardProvider(this).scoreDirection(ScoreDirection.UP).build());
	}

	@Override
	public String getTitle(Player player)
	{
		return ChatColor.GREEN + "Iris";
	}

	@Override
	public List<String> getLines(Player player)
	{
		World world = player.getWorld();
		List<String> lines = new ArrayList<>();

		if(world.getGenerator() instanceof IrisGenerator)
		{
			IrisGenerator g = (IrisGenerator) world.getGenerator();
			int x = player.getLocation().getBlockX();
			int z = player.getLocation().getBlockZ();
			IrisDimension dim = g.getDimension();
			BiomeResult er = g.getBiome(x, z);
			IrisBiome b = er != null ? er.getBiome() : null;
			int fh = dim.getFluidHeight();
			lines.add("&7&m-----------------");
			lines.add(ChatColor.GREEN + "Speed" + ChatColor.GRAY + ": " + ChatColor.BOLD + "" + ChatColor.GRAY + Form.f(g.getMetrics().getPerSecond().getAverage(), 0) + "/s " + Form.duration(g.getMetrics().getTotal().getAverage(), 1) + "");
			lines.add(ChatColor.GREEN + "Loss" + ChatColor.GRAY + ": " + ChatColor.BOLD + "" + ChatColor.GRAY + Form.duration(g.getMetrics().getLoss().getAverage(), 4) + "");
			lines.add(ChatColor.GREEN + "Generators" + ChatColor.GRAY + ": " + Form.f(CNG.creates));
			lines.add(ChatColor.GREEN + "Noise" + ChatColor.GRAY + ": " + Form.f((int) hits.getAverage()));
			lines.add(ChatColor.GREEN + "Parallax Regions" + ChatColor.GRAY + ": " + Form.f((int) g.getParallaxMap().getLoadedRegions().size()));
			lines.add(ChatColor.GREEN + "Parallax Chunks" + ChatColor.GRAY + ": " + Form.f((int) g.getParallaxMap().getLoadedChunks().size()));
			lines.add(ChatColor.GREEN + "Sliver Buffer" + ChatColor.GRAY + ": " + Form.f((int) g.getSliverBuffer()));

			if(er != null && b != null)
			{
				lines.add(ChatColor.GREEN + "Biome" + ChatColor.GRAY + ": " + b.getName());
				lines.add(ChatColor.GREEN + "File" + ChatColor.GRAY + ": " + b.getLoadKey() + ".json");
				lines.add(ChatColor.GREEN + "Height" + ChatColor.GRAY + ": " + (int) (b.getLowHeight() + fh) + " - " + (int) (b.getHighHeight() + fh) + " (" + (int) (b.getHighHeight() - b.getLowHeight()) + ")");
			}
			lines.add("&7&m-----------------");
		}

		else
		{
			lines.add(ChatColor.GREEN + "Join an Iris World!");
		}

		return lines;
	}

	public void onDisable()
	{
		for(GroupedExecutor i : executors)
		{
			i.close();
		}

		executors.clear();
		manager.onDisable();
		Bukkit.getScheduler().cancelTasks(this);
		HandlerList.unregisterAll((Plugin) this);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
	{
		if(command.getName().equals("iris"))
		{
			if(args.length == 0)
			{
				imsg(sender, "/iris dev - Create a new dev world");
				imsg(sender, "/iris wand - Get a wand");
			}

			if(args.length >= 1)
			{
				if(args[0].equalsIgnoreCase("wand"))
				{
					((Player) sender).getInventory().addItem(WandController.createWand());
				}

				if(args[0].equalsIgnoreCase("save") && args.length >= 2)
				{
					ItemStack wand = ((Player) sender).getInventory().getItemInMainHand();
					IrisObject o = WandController.createSchematic(wand);
					try
					{
						o.write(new File(getDataFolder(), "objects/" + args[1] + ".iob"));
						imsg(sender, "Saved " + "objects/" + args[1] + ".iob");
					}

					catch(IOException e)
					{
						imsg(sender, "Failed to save " + "objects/" + args[1] + ".iob");

						e.printStackTrace();
					}
				}

				if(args[0].equalsIgnoreCase("load") && args.length >= 2)
				{
					File file = new File(getDataFolder(), "objects/" + args[1] + ".iob");
					boolean intoWand = false;

					for(String i : args)
					{
						if(i.equalsIgnoreCase("-edit"))
						{
							intoWand = true;
						}
					}

					if(!file.exists())
					{
						imsg(sender, "Can't find " + "objects/" + args[1] + ".iob");
					}

					ItemStack wand = ((Player) sender).getInventory().getItemInMainHand();
					IrisObject o = new IrisObject(0, 0, 0);

					try
					{
						o.read(new File(getDataFolder(), "objects/" + args[1] + ".iob"));
						imsg(sender, "Loaded " + "objects/" + args[1] + ".iob");
						Location block = ((Player) sender).getTargetBlock((Set<Material>) null, 256).getLocation().clone().add(0, 1, 0);

						if(intoWand && WandController.isWand(wand))
						{
							wand = WandController.createWand(block.clone().subtract(o.getCenter()).add(o.getW() - 1, o.getH(), o.getD() - 1), block.clone().subtract(o.getCenter()));
							((Player) sender).getInventory().setItemInMainHand(wand);
							imsg(sender, "Updated wand for " + "objects/" + args[1] + ".iob");
						}

						WandController.pasteSchematic(o, block);
						imsg(sender, "Placed " + "objects/" + args[1] + ".iob");
					}

					catch(IOException e)
					{
						imsg(sender, "Failed to load " + "objects/" + args[1] + ".iob");
						e.printStackTrace();
					}
				}

				if(args[0].equalsIgnoreCase("dev"))
				{
					String dim = "Overworld";

					if(args.length > 1)
					{
						dim = args[1];
					}

					String dimm = dim;

					Bukkit.getScheduler().scheduleSyncDelayedTask(this, () ->
					{
						for(World i : Bukkit.getWorlds())
						{
							if(i.getName().startsWith("iris/"))
							{
								for(Player j : Bukkit.getOnlinePlayers())
								{
									imsg(j, "Unloading " + i.getName());
								}

								Bukkit.unloadWorld(i, false);
							}
						}

						for(Player i : Bukkit.getOnlinePlayers())
						{
							imsg(i, "Creating Iris " + dimm + "...");
						}

						IrisGenerator gx = new IrisGenerator("overworld", 16);

						O<Boolean> done = new O<Boolean>();
						done.set(false);

						J.a(() ->
						{
							int req = 740;
							while(!done.get())
							{
								for(Player i : Bukkit.getOnlinePlayers())
								{
									imsg(i, "Generating " + Form.pc((double) gx.getGenerated() / (double) req));
								}
								J.sleep(3000);
							}
						});
						World world = Bukkit.createWorld(new WorldCreator("iris/" + UUID.randomUUID()).generator(gx));
						done.set(true);

						for(Player i : Bukkit.getOnlinePlayers())
						{
							imsg(i, "Generating 100%");
						}

						for(Player i : Bukkit.getOnlinePlayers())
						{
							i.teleport(new Location(world, 0, 100, 0));

							Bukkit.getScheduler().scheduleSyncDelayedTask(this, () ->
							{
								imsg(i, "Have Fun!");
								i.setGameMode(GameMode.SPECTATOR);
							}, 5);
						}
					});
				}
			}

			return true;
		}

		return false;
	}

	public void imsg(CommandSender s, String msg)
	{
		s.sendMessage(ChatColor.GREEN + "[" + ChatColor.DARK_GRAY + "Iris" + ChatColor.GREEN + "]" + ChatColor.GRAY + ": " + msg);
	}

	@Override
	public ChunkGenerator getDefaultWorldGenerator(String worldName, String id)
	{
		return new IrisGenerator("overworld", 16);
	}

	public static void msg(String string)
	{
		String msg = ChatColor.GREEN + "[Iris]: " + ChatColor.GRAY + string;

		if(last.equals(msg))
		{
			return;
		}

		last = msg;

		Bukkit.getConsoleSender().sendMessage(msg);
	}

	public static void warn(String string)
	{
		msg(ChatColor.YELLOW + string);
	}

	public static void error(String string)
	{
		msg(ChatColor.RED + string);
	}

	public static void verbose(String string)
	{
		msg(ChatColor.GRAY + string);
	}

	public static void success(String string)
	{
		msg(ChatColor.GREEN + string);
	}

	public static void info(String string)
	{
		msg(ChatColor.WHITE + string);
	}

	public void hit(long hits2)
	{
		hits.put(hits2);
	}
}
