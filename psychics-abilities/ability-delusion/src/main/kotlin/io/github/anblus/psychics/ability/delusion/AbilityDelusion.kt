package io.github.anblus.psychics.ability.delusion

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.ProtocolManager
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import com.comphenix.protocol.wrappers.WrappedBlockData
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import io.github.monun.psychics.*
import io.github.monun.psychics.util.hostileFilter
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

@Name("delusion")
class AbilityConceptDelusion : AbilityConcept() {

    init {
        displayName = "망상"
        type = AbilityType.ACTIVE
        cost = 50.0
        range = 8.0
        cooldownTime = 20000L
        durationTime = 10000L
        description = listOf(
            text("대상을 망상 상태에 빠뜨립니다."),
            text("망상 속에서, 대상은 주위 모든 블록이 검은색 콘크리트로 보이고,"),
            text("주위의 다른 엔티티들이 전부 보이지 않으며,"),
            text("특정 백그라운드 음악이 재생됩니다.")
        )
        wand = ItemStack(Material.ENDER_EYE)
    }
}

class AbilityDelusion : ActiveAbility<AbilityConceptDelusion>(), Listener {

    private val protocolManager: ProtocolManager = ProtocolLibrary.getProtocolManager()
    private val activeListeners = mutableMapOf<Player, PacketAdapter>()

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    override fun onDisable() {
        activeListeners.forEach {
            it.key.stopDelusion()
        }
    }

    override fun onInitialize() {
        targeter = {
            /*

            val player = esper.player
            val start = player.eyeLocation
            val world = start.world

            world.rayTrace(
                start,
                start.direction,
                concept.range,
                FluidCollisionMode.NEVER,
                true,
                0.5,
                player.hostileFilter()
            )?.hitEntity?.let { entity ->
                if (entity is Player) entity else null
            }

             */

            esper.player
        }
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        val player = esper.player

        if (activeListeners.containsKey(target)) {
            player.sendActionBar(text().content("대상이 이미 망상 상태에 빠져있습니다").decorate(TextDecoration.BOLD).build())
            return
        }

        val world = player.world

        world.playSound(player.location, Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.5f)
        world.spawnParticle(Particle.SPELL_WITCH, player.location, 50, 1.0, 1.0, 1.0, 0.1)

        psychic.consumeMana(concept.cost)
        cooldownTime = concept.cooldownTime

        (target as Player).startDelusion()
    }

    private fun Player.startDelusion() {
        playSound(location, Sound.MUSIC_CREDITS, 1.0f, 1.0f)
`
        val listener = object : PacketAdapter(psychic.plugin, PacketType.Play.Server.ENTITY_METADATA, PacketType.Play.Server.ENTITY_METADATA) {
            override fun onPacketSending(event: PacketEvent) {
                if (event.player != this@startDelusion) return

                when (event.packetType) {
                    PacketType.Play.Server.MAP_CHUNK -> handleMapChunkPacket(event)
                    PacketType.Play.Server.ENTITY_METADATA -> handleEntityMetadataPacket(event)
                }
            }
        }

        protocolManager.addPacketListener(listener)
        activeListeners[this@startDelusion] = listener

        psychic.runTask({
            stopDelusion()
        }, concept.durationTime / 50L)
    }

    private fun handleMapChunkPacket(event: PacketEvent) {
        val packet = event.packet

        packet.getItem
        val chunkData = packet.getSpecificModifier(ByteArray::class.java).read(0)
        for (i in chunkData.indices) {
            if (chunkData[i].toInt() != Material.BLACK_CONCRETE.id) {
                chunkData[i] = Material.BLACK_CONCRETE.id.toByte()
            }
        }

        packet.getSpecificModifier(ByteArray::class.java).write(0, chunkData)
    }

    private fun handleEntityMetadataPacket(event: PacketEvent) {
        val packet = event.packet
        val entityID = packet.integers.read(0)
        val watcher = WrappedDataWatcher(packet.watchableCollectionModifier.read(0))

        watcher.setObject(0, WrappedDataWatcher.Registry.get(Byte::class.java), 0x20.toByte())

        packet.watchableCollectionModifier.write(0, watcher.watchableObjects)
    }

    private fun Player.stopDelusion() {
        stopSound(Sound.MUSIC_CREDITS)

        activeListeners[this@stopDelusion]?.let {
            protocolManager.removePacketListener(it)
            activeListeners.remove(this@stopDelusion)
        }

        world.playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
    }
}