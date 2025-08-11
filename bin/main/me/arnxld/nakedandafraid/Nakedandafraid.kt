package me.arnxld.nakedandafraid

import org.bukkit.*
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.IOException
import java.util.*

class Nakedandafraid : JavaPlugin(), Listener {
    
    private val armoredPlayers = mutableSetOf<UUID>()
    private var poisonTask: BukkitTask? = null
    
    private lateinit var dataFile: File
    private lateinit var dataConfig: FileConfiguration
    private val deadPlayers = mutableSetOf<UUID>()
    
    override fun onEnable() {
        setupDataFile()
        loadDeadPlayers()
        
        server.pluginManager.registerEvents(this, this)
        
        startPoisonTask()
        
        logger.info("Naked and Afraid plugin enabled!")
    }

    override fun onDisable() {
        poisonTask?.cancel()
        
        saveDeadPlayers()
        
        logger.info("Naked and Afraid plugin disabled!")
    }
    
    private fun setupDataFile() {
        dataFile = File(dataFolder, "data.yml")
        if (!dataFile.exists()) {
            dataFile.parentFile.mkdirs()
            try {
                dataFile.createNewFile()
            } catch (e: IOException) {
                logger.severe("Could not create data.yml: ${e.message}")
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile)
    }
    
    private fun loadDeadPlayers() {
        deadPlayers.clear()
        val deadPlayersList = dataConfig.getStringList("dead-players")
        for (uuidString in deadPlayersList) {
            try {
                deadPlayers.add(UUID.fromString(uuidString))
            } catch (e: IllegalArgumentException) {
                logger.warning("Invalid UUID in data.yml: $uuidString")
            }
        }
        logger.info("Loaded ${deadPlayers.size} dead players from data.yml")
    }
    
    private fun saveDeadPlayers() {
        val deadPlayersList = deadPlayers.map { it.toString() }
        dataConfig.set("dead-players", deadPlayersList)
        try {
            dataConfig.save(dataFile)
        } catch (e: IOException) {
            logger.severe("Could not save data.yml: ${e.message}")
        }
    }
    
    private fun addDeadPlayer(player: Player) {
        deadPlayers.add(player.uniqueId)
        saveDeadPlayers()
    }
    
    private fun removeDeadPlayer(player: Player) {
        deadPlayers.remove(player.uniqueId)
        saveDeadPlayers()
    }
    
    private fun isPlayerDead(player: Player): Boolean {
        return deadPlayers.contains(player.uniqueId)
    }
    
    private fun startPoisonTask() {
        poisonTask = server.scheduler.runTaskTimer(this, Runnable {
            for (player in server.onlinePlayers) {
                if (hasArmor(player)) {
                    if (!armoredPlayers.contains(player.uniqueId)) {
                        armoredPlayers.add(player.uniqueId)
                    }
                    player.addPotionEffect(PotionEffect(PotionEffectType.POISON, 60, 0, false, true))
                } else {
                    if (armoredPlayers.contains(player.uniqueId)) {
                        armoredPlayers.remove(player.uniqueId)
                    }
                }
            }
        }, 20L, 20L)
    }
    
    private fun hasArmor(player: Player): Boolean {
        val armor = player.inventory.armorContents
        return armor.any { item -> item != null && item.type != Material.AIR }
    }
    
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        
        if (isPlayerDead(player)) {
            player.gameMode = GameMode.SPECTATOR
            player.sendMessage("${ChatColor.RED}You are dead! Wait for an OP to revive you with /revive ${player.name}")
            server.broadcastMessage("${ChatColor.DARK_RED}${player.name} has joined but remains dead. Use /revive ${player.name} to bring them back!")
        }
        
        if (hasArmor(player)) {
            armoredPlayers.add(player.uniqueId)
        }
    }
    
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        armoredPlayers.remove(event.player.uniqueId)
    }
    
    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        
        player.gameMode = GameMode.SPECTATOR
        
        addDeadPlayer(player)
        
        armoredPlayers.remove(player.uniqueId)
        
    }
    
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        
        server.scheduler.runTaskLater(this, Runnable {
            if (hasArmor(player)) {
                if (!armoredPlayers.contains(player.uniqueId)) {
                    armoredPlayers.add(player.uniqueId)
                }
            } else {
                if (armoredPlayers.contains(player.uniqueId)) {
                    armoredPlayers.remove(player.uniqueId)
                }
            }
        }, 1L)
    }
    
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name.equals("revive", ignoreCase = true)) {
            return handleReviveCommand(sender, args)
        }
        return false
    }
    
    private fun handleReviveCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            sender.sendMessage("${ChatColor.RED}You don't have permission to use this command!")
            return true
        }
        
        if (args.isEmpty()) {
            sender.sendMessage("${ChatColor.RED}Usage: /revive <player>")
            return true
        }
        
        val targetName = args[0]
        val target = server.getPlayer(targetName)
        
        if (target == null) {
            sender.sendMessage("${ChatColor.RED}Player '$targetName' not found or not online!")
            return true
        }
        
        if (target.gameMode != GameMode.SPECTATOR && !isPlayerDead(target)) {
            sender.sendMessage("${ChatColor.RED}${target.name} is not dead!")
            return true
        }
        
        removeDeadPlayer(target)
        
        target.gameMode = GameMode.SURVIVAL
        target.health = target.maxHealth
        target.foodLevel = 20
        target.saturation = 20f
        
        for (effect in target.activePotionEffects) {
            target.removePotionEffect(effect.type)
        }
        
        if (sender is Player) {
            target.teleport(sender.location)
        } else {
            target.teleport(target.world.spawnLocation)
        }
                
        return true
    }
}
