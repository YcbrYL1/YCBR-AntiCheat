package com.ycbr.anticheat.packet;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.ycbr.anticheat.check.CheckType;
import com.ycbr.anticheat.check.movement.VelocityCheck;
import com.ycbr.anticheat.core.AntiCheatManager;
import com.ycbr.anticheat.data.PlayerData;
import com.ycbr.anticheat.data.context.AttackContext;
import com.ycbr.anticheat.data.context.MoveContext;
import com.ycbr.anticheat.data.context.PlaceContext;
import com.ycbr.anticheat.util.MathUtil;

public final class AsyncPacketListener {

    private final AntiCheatManager manager;
    private ProtocolManager protocolManager;
    private PacketAdapter incoming;
    private PacketAdapter outgoing;

    public AsyncPacketListener(AntiCheatManager manager) {
        this.manager = manager;
    }

    public void start() {
        protocolManager = ProtocolLibrary.getProtocolManager();
        if (incoming != null) {
            protocolManager.removePacketListener(incoming);
        }
        if (outgoing != null) {
            protocolManager.removePacketListener(outgoing);
        }

        incoming = new PacketAdapter(manager.getPlugin(), ListenerPriority.HIGH,
                PacketType.Play.Client.POSITION, PacketType.Play.Client.POSITION_LOOK, PacketType.Play.Client.LOOK,
                PacketType.Play.Client.USE_ENTITY, PacketType.Play.Client.BLOCK_PLACE,
                PacketType.Play.Client.BLOCK_DIG, PacketType.Play.Client.CLIENT_COMMAND,
                PacketType.Play.Client.ARM_ANIMATION, PacketType.Play.Client.ENTITY_ACTION,
                PacketType.Play.Client.STEER_VEHICLE, PacketType.Play.Client.KEEP_ALIVE,
                PacketType.Play.Client.HELD_ITEM_SLOT) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) {
                    return;
                }
                Player player = event.getPlayer();
                if (player == null) {
                    return;
                }
                PlayerData data = manager.getDataManager().get(player.getUniqueId());
                data.lastActive = System.currentTimeMillis();
                if (!data.authenticated && manager.getAuthManager().enabled()
                        && isAuthBlocked(event.getPacketType())) {
                    event.setCancelled(true);
                    return;
                }
                PacketContainer packet = event.getPacket();
                if (event.getPacketType() == PacketType.Play.Client.POSITION) {
                    handlePosition(data, packet, false);
                } else if (event.getPacketType() == PacketType.Play.Client.POSITION_LOOK) {
                    handlePosition(data, packet, true);
                } else if (event.getPacketType() == PacketType.Play.Client.LOOK) {
                    handleLook(data, packet);
                } else if (event.getPacketType() == PacketType.Play.Client.USE_ENTITY) {
                    handleUseEntity(data, player, packet);
                } else if (event.getPacketType() == PacketType.Play.Client.ARM_ANIMATION) {
                    data.actor.submit(() -> {
                        data.lastSwingTime = mono(data);
                        data.lastSwingPositionCount = data.positionCount;
                    });
                } else if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
                    handleEntityAction(data, packet);
                } else if (event.getPacketType() == PacketType.Play.Client.STEER_VEHICLE) {
                    handleSteerVehicle(data, packet);
                } else if (event.getPacketType() == PacketType.Play.Client.KEEP_ALIVE) {
                    handleKeepAlive(data, packet);
                } else if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_SLOT) {
                    handleHeldItemSlot(data, packet);
                } else if (event.getPacketType() == PacketType.Play.Client.BLOCK_PLACE) {
                    handleBlockPlace(data, packet);
                } else if (event.getPacketType() == PacketType.Play.Client.BLOCK_DIG) {
                    handleBlockDig(data, packet);
                } else if (event.getPacketType() == PacketType.Play.Client.CLIENT_COMMAND) {
                    handleClientCommand(data, packet);
                }
            }
        };
        protocolManager.addPacketListener(incoming);

        outgoing = new PacketAdapter(manager.getPlugin(), ListenerPriority.MONITOR,
                PacketType.Play.Server.ENTITY_VELOCITY, PacketType.Play.Server.POSITION) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.getPacketType() == PacketType.Play.Server.POSITION) {
                    try {
                        PlayerData data = manager.getDataManager().get(event.getPlayer().getUniqueId());
                        data.lastTeleportX = event.getPacket().getDoubles().read(0);
                        data.lastTeleportY = event.getPacket().getDoubles().read(1);
                        data.lastTeleportZ = event.getPacket().getDoubles().read(2);
                        data.lastTeleportTime = System.currentTimeMillis();
                    } catch (Exception ignored) {
                    }
                    return;
                }
                PacketContainer packet = event.getPacket();
                if (packet.getIntegers().read(0) != event.getPlayer().getEntityId()) {
                    return;
                }
                PlayerData data = manager.getDataManager().get(event.getPlayer().getUniqueId());
                data.kbPreSpeed = data.movement.lastDistanceXZ;
                data.lastKbTime = System.currentTimeMillis();
                data.velocity.issue(packet.getIntegers().read(1) / 8000D, packet.getIntegers().read(2) / 8000D,
                        packet.getIntegers().read(3) / 8000D);
                if (manager.config().raw().getBoolean("settings.debug-velocity", false)) {
                    Bukkit.getConsoleSender().sendMessage("§8[YCBR-VEL] §7KB issued vx="
                            + MathUtil.round(packet.getIntegers().read(1) / 8000D, 3) + " vy="
                            + MathUtil.round(packet.getIntegers().read(2) / 8000D, 3) + " vz="
                            + MathUtil.round(packet.getIntegers().read(3) / 8000D, 3)
                            + " pre=" + MathUtil.round(data.kbPreSpeed, 3));
                }
                ((VelocityCheck) manager.getRegistry().get(CheckType.VELOCITY)).onKbIssued(data);
            }
        };
        protocolManager.addPacketListener(outgoing);
    }

    public void stop() {
        if (protocolManager != null) {
            protocolManager.removePacketListener(incoming);
            protocolManager.removePacketListener(outgoing);
        }
    }

    private void handlePosition(PlayerData data, PacketContainer packet, boolean withRotation) {
        double x = packet.getDoubles().read(0);
        double y = packet.getDoubles().read(1);
        double z = packet.getDoubles().read(2);
        if (invalidPosition(x) || invalidPosition(y) || invalidPosition(z)) {
            manager.getRegistry().onBadPacket(data, x, y, z);
            return;
        }
        data.lastPositionMillis = System.currentTimeMillis();
        float yaw = (float) data.lastYaw;
        float pitch = (float) data.lastPitch;
        if (withRotation) {
            yaw = ((Number) packet.getModifier().read(3)).floatValue();
            pitch = ((Number) packet.getModifier().read(4)).floatValue();
            data.lastYaw = yaw;
            data.lastPitch = pitch;
            data.hasRotation = true;
        }
        final float fYaw = yaw;
        final float fPitch = pitch;
        boolean onGround = false;
        try {
            onGround = packet.getBooleans().read(0);
        } catch (Exception ignored) {
        }
        final boolean fOnGround = onGround;
        final long fArrival = System.currentTimeMillis();
        data.actor.submit(() -> {
            data.monoClock = mono(data);
            data.positionCount++;
            data.clientOnGround = fOnGround;
            if (withRotation) {
                double turnYaw = Math.abs(MathUtil.normalizeYaw(fYaw - data.lastYaw));
                double turnPitch = Math.abs(fPitch - data.lastPitch);
                if (turnYaw > 40D || turnPitch > 20D) {
                    data.lastBigTurnTime = System.currentTimeMillis();
                }
            }
            if (data.movement.handle(x, y, z, data.blockOnIce, data.blockOnSlime, data.blockNearLiquid,
                    data.blockBoxedIn, data.blockInWeb, data.blockOnLadder)) {
                data.velocity.expire();
            }
            data.lastPacketTime = System.currentTimeMillis();
            if (withRotation) {
                data.lastRotationTime = System.currentTimeMillis();
                manager.getRegistry().onRotation(data, fYaw, fPitch);
            }
            manager.getRegistry().onMove(new MoveContext(data, x, y, z, fYaw, fPitch, fArrival));
        });
    }

    private static boolean invalidPosition(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) || Math.abs(value) >= 2147483647.0D;
    }

    private static boolean isAuthBlocked(PacketType type) {
        return type == PacketType.Play.Client.POSITION
                || type == PacketType.Play.Client.POSITION_LOOK
                || type == PacketType.Play.Client.USE_ENTITY
                || type == PacketType.Play.Client.BLOCK_DIG
                || type == PacketType.Play.Client.BLOCK_PLACE
                || type == PacketType.Play.Client.STEER_VEHICLE;
    }

    private static long mono(PlayerData data) {
        long raw = System.currentTimeMillis();
        long last = data.monoClock;
        if (raw > last) {
            data.monoClock = raw;
            return raw;
        }
        return last;
    }

    private void handleLook(PlayerData data, PacketContainer packet) {
        float yaw;
        float pitch;
        try {
            yaw = ((Number) packet.getModifier().read(0)).floatValue();
            pitch = ((Number) packet.getModifier().read(1)).floatValue();
        } catch (Exception e) {
            return;
        }
        boolean onGround = false;
        try {
            onGround = packet.getBooleans().read(0);
        } catch (Exception ignored) {
        }
        final float fYaw = yaw;
        final float fPitch = pitch;
        final boolean fOnGround = onGround;
        data.actor.submit(() -> {
            data.lookOnGround = fOnGround;
            manager.getRegistry().onRotation(data, fYaw, fPitch);
            data.lastYaw = fYaw;
            data.lastPitch = fPitch;
            data.hasRotation = true;
            data.lastRotationTime = System.currentTimeMillis();
            if (fOnGround) {
                manager.getRegistry().onLook(data, true);
            }
            if (!data.hasPrevRotation) {
                data.prevYaw = fYaw;
                data.prevPitch = fPitch;
                data.hasPrevRotation = true;
                return;
            }
            double pitchDelta = Math.abs(fPitch - data.prevPitch);
            data.lastYawDelta = MathUtil.normalizeYaw(fYaw - data.prevYaw);
            data.prevYaw = fYaw;
            data.prevPitch = fPitch;
            if (Math.abs(data.lastYawDelta) > 40D || pitchDelta > 20D) {
                data.lastBigTurnTime = System.currentTimeMillis();
            }
        });
    }

    private void handleEntityAction(PlayerData data, PacketContainer packet) {
        int action;
        try {
            action = packet.getPlayerActions().read(0).ordinal();
        } catch (Exception e) {
            try {
                Object raw = packet.getModifier().read(0);
                if (raw instanceof Number) {
                    action = ((Number) raw).intValue();
                } else {
                    String name = raw.toString().toUpperCase();
                    if (name.contains("START_SPRINTING")) {
                        action = 3;
                    } else if (name.contains("STOP_SPRINTING")) {
                        action = 4;
                    } else {
                        return;
                    }
                }
            } catch (Exception e2) {
                return;
            }
        }
        final int fAction = action;
        data.actor.submit(() -> {
            long now = System.currentTimeMillis();
            if (fAction == 3) {
                data.lastSprintStartTime = now;
                data.movement.sprinting = true;
                ((VelocityCheck) manager.getRegistry().get(CheckType.VELOCITY)).checkSprintReset(data, now);
            } else if (fAction == 4) {
                data.lastSprintStopTime = now;
                data.movement.sprinting = false;
            }
            if (fAction == 5) {
                manager.getRegistry().onRidingJump(data, now);
            } else {
                manager.getRegistry().onSprintAction(data, fAction);
            }
        });
    }

    private void handleUseEntity(PlayerData data, Player player, PacketContainer packet) {
        int targetId = packet.getIntegers().read(0);
        boolean attack = isAttack(packet);
        data.actor.submit(() -> {
            data.useEntityCount++;
            if (attack) {
                manager.getRegistry().onAttack(new AttackContext(data, targetId,
                        System.currentTimeMillis(), player.getEntityId()));
            }
        });
    }

    private boolean isAttack(PacketContainer packet) {
        try {
            return packet.getEntityUseActions().read(0) == EnumWrappers.EntityUseAction.ATTACK;
        } catch (Exception e) {
            try {
                Object action = packet.getModifier().read(1);
                if (action instanceof Number) {
                    return ((Number) action).intValue() == 0;
                }
                return action.toString().contains("ATTACK");
            } catch (Exception e2) {
                return true;
            }
        }
    }

    private void handleBlockPlace(PlayerData data, PacketContainer packet) {
        int[] pos = readBlockPosition(packet);
        if (pos == null) {
            return;
        }
        int direction = 0;
        try {
            Object raw = packet.getModifier().read(1);
            if (raw instanceof Number) {
                direction = ((Number) raw).intValue();
            } else if (raw != null) {
                String name = raw.toString().toUpperCase();
                if (name.contains("DOWN")) {
                    direction = 0;
                } else if (name.contains("UP")) {
                    direction = 1;
                } else if (name.contains("NORTH")) {
                    direction = 2;
                } else if (name.contains("SOUTH")) {
                    direction = 3;
                } else if (name.contains("WEST")) {
                    direction = 4;
                } else if (name.contains("EAST")) {
                    direction = 5;
                }
            }
        } catch (Exception ignored) {
        }
        boolean hasCursor = false;
        double cursorX = 0D;
        double cursorY = 0D;
        double cursorZ = 0D;
        try {
            cursorX = packet.getFloat().read(0);
            cursorY = packet.getFloat().read(1);
            cursorZ = packet.getFloat().read(2);
            hasCursor = true;
        } catch (Exception ignored) {
        }
        final int fDirection = direction;
        final boolean fHasCursor = hasCursor;
        final double fCursorX = cursorX;
        final double fCursorY = cursorY;
        final double fCursorZ = cursorZ;
        data.actor.submit(() -> {
            data.usingItem = true;
            data.lastItemUseTime = System.currentTimeMillis();
            Material held = null;
            try {
                Player p = Bukkit.getPlayer(data.getUuid());
                if (p != null && p.getItemInHand() != null) {
                    held = p.getItemInHand().getType();
                }
            } catch (Exception ignored) {
            }
            data.blockingSword = held == Material.WOOD_SWORD || held == Material.STONE_SWORD
                    || held == Material.IRON_SWORD || held == Material.GOLD_SWORD
                    || held == Material.DIAMOND_SWORD;
            manager.getRegistry().onPlace(new PlaceContext(data, pos[0], pos[1], pos[2],
                    fDirection, System.currentTimeMillis(), fHasCursor, fCursorX, fCursorY, fCursorZ));
        });
    }

    private void handleBlockDig(PlayerData data, PacketContainer packet) {
        final int[] dig = readDig(packet);
        if (dig == null) {
            return;
        }
        final int status = dig[0];
        final int face = dig[1];
        data.actor.submit(() -> {
            if (status == 0) {
                data.digging = true;
                data.lastDigStartTime = System.currentTimeMillis();
                manager.getRegistry().onBlockDigStart(data);
            } else if (status == 1 || status == 2) {
                data.digging = false;
            } else if (status == 5) {
                data.usingItem = false;
            }
            manager.getRegistry().onDigFace(data, status, face);
        });
    }

    private int[] readDig(PacketContainer packet) {
        try {
            Object statusRaw = packet.getModifier().read(2);
            String statusName = statusRaw.toString().toUpperCase();
            if (statusName.contains("DESTROY") || statusName.contains("DROP")
                    || statusName.contains("RELEASE") || statusName.contains("SWAP")) {
                int status;
                if (statusName.contains("START_DESTROY")) {
                    status = 0;
                } else if (statusName.contains("ABORT") || statusName.contains("CANCEL")) {
                    status = 1;
                } else if (statusName.contains("STOP_DESTROY")) {
                    status = 2;
                } else if (statusName.contains("RELEASE")) {
                    status = 5;
                } else {
                    return null;
                }
                int face = readFace(packet.getModifier().read(1));
                return new int[] { status, face };
            }
        } catch (Exception ignored) {
        }
        try {
            Object faceRaw = packet.getModifier().read(3);
            Object statusRaw = packet.getModifier().read(4);
            if (faceRaw instanceof Number && statusRaw instanceof Number) {
                return new int[] { ((Number) statusRaw).intValue(), ((Number) faceRaw).intValue() };
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int readFace(Object raw) {
        if (raw == null) {
            return -1;
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        String name = raw.toString().toUpperCase();
        if (name.contains("DOWN")) {
            return 0;
        }
        if (name.contains("UP")) {
            return 1;
        }
        if (name.contains("NORTH")) {
            return 2;
        }
        if (name.contains("SOUTH")) {
            return 3;
        }
        if (name.contains("WEST")) {
            return 4;
        }
        if (name.contains("EAST")) {
            return 5;
        }
        return -1;
    }

    private void handleHeldItemSlot(PlayerData data, PacketContainer packet) {
        int slot;
        try {
            slot = packet.getShorts().read(0);
        } catch (Exception e) {
            try {
                slot = packet.getIntegers().read(0);
            } catch (Exception e2) {
                return;
            }
        }
        final int fSlot = slot;
        data.actor.submit(() -> {
            data.lastSlotChangeTime = System.currentTimeMillis();
            manager.getRegistry().onHeldItemSlot(data, fSlot);
        });
    }

    private void handleKeepAlive(PlayerData data, PacketContainer packet) {
        long id;
        try {
            id = packet.getIntegers().read(0);
        } catch (Exception e) {
            try {
                id = packet.getLongs().read(0);
            } catch (Exception e2) {
                return;
            }
        }
        final long fId = id;
        data.actor.submit(() -> manager.getRegistry().onKeepAlive(data, fId));
    }

    private void handleSteerVehicle(PlayerData data, PacketContainer packet) {
        final float forward;
        final float sideways;
        final boolean jump;
        final boolean unmount;
        try {
            sideways = packet.getFloat().read(0);
            forward = packet.getFloat().read(1);
        } catch (Exception e) {
            return;
        }
        try {
            jump = packet.getBooleans().read(0);
            unmount = packet.getBooleans().read(1);
        } catch (Exception e) {
            return;
        }
        data.actor.submit(() -> manager.getRegistry().onSteerVehicle(data, forward, sideways, jump, unmount));
    }

    private void handleClientCommand(PlayerData data, PacketContainer packet) {
        final int action = readClientCommand(packet);
        if (action != 2) {
            return;
        }
        data.actor.submit(() -> manager.getRegistry().onClientCommand(data, 2));
    }

    private int readClientCommand(PacketContainer packet) {
        try {
            Object raw = packet.getModifier().read(0);
            if (raw instanceof Number) {
                return ((Number) raw).intValue();
            }
            String name = raw.toString().toUpperCase();
            if (name.contains("OPEN_INVENTORY") || name.contains("OPEN_INVENTORY_ACHIEVEMENT")) {
                return 2;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private int[] readBlockPosition(PacketContainer packet) {
        try {
            BlockPosition bp = packet.getBlockPositionModifier().read(0);
            return new int[] { bp.getX(), bp.getY(), bp.getZ() };
        } catch (Exception e) {
            try {
                return packet.getIntegerArrays().read(0);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}