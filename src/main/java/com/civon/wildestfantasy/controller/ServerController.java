package com.civon.wildestfantasy.controller;

import com.civon.wildestfantasy.collar.CollarService;
import com.civon.wildestfantasy.network.ControlActionPayload;
import com.civon.wildestfantasy.network.PanelStatePayload;
import com.civon.wildestfantasy.network.PeerStrengthPayload;
import com.civon.wildestfantasy.network.PeerStrengthReportPayload;
import com.civon.wildestfantasy.network.RemoteCommandPayload;
import com.civon.wildestfantasy.network.ReportLimitsPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端侧的控制关系中枢。
 *
 * <p>它只做三件事：
 * <ol>
 *   <li>记住每个玩家上报的舒适/绝对上限（{@link #LIMITS}）；</li>
 *   <li>校验「发指令的人真的是目标项圈的主人」，然后转成一条
 *       {@link RemoteCommandPayload} 发给目标客户端；</li>
 *   <li>把面板需要的状态回填给主人（{@link PanelStatePayload}）。</li>
 * </ol>
 *
 * <p>被控者不在线、项圈不在了、或者主人换了人，所有指令都会被静默丢弃 ——
 * 客户端只用自己手里的 DGLab 连接，服务端永远不直接碰设备。
 */
public final class ServerController {
    /** 没上报过时的兜底：舒适 40、绝对 200（协议硬顶）。 */
    private static final int DEFAULT_COMFORT = 40;
    private static final int DEFAULT_ABSOLUTE = 200;

    /** uuid -> { comfortMax, absoluteMax } */
    private static final Map<UUID, int[]> LIMITS = new ConcurrentHashMap<>();
    /** 被控者 uuid -> 主人设定的持续基础强度 */
    private static final Map<UUID, Integer> BASE = new ConcurrentHashMap<>();
    /** 被控者 uuid -> 血量映射是否开启（默认开启） */
    private static final Map<UUID, Boolean> HP_ENABLED = new ConcurrentHashMap<>();
    /**
     * 主人 uuid -> 被控者 uuid，以及反向索引。
     *
     * <p>关系记在<b>玩家</b>身上而不是物品上（用户要求）：控制器丢了、换了，
     * 只要这条关系还在，控制权就还在。物品 NBT 里的 subject 只是副本，用于界面显示与重启后兜底。
     */
    private static final Map<UUID, UUID> RELATIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> OWNER_OF = new ConcurrentHashMap<>();
    /** 被控者 uuid -> 他那边的郊狼设备是否已连接（用于主人面板显示） */
    private static final Map<UUID, Boolean> SUBJECT_CONNECTED = new ConcurrentHashMap<>();
    /** 被控者 uuid -> { A 通道强度, B 通道强度 }，转给主人 HUD 显示 */
    private static final Map<UUID, int[]> PEER_STRENGTH = new ConcurrentHashMap<>();

    private ServerController() {
    }

    // ------------------------------------------------------------------ 上限

    public static void handleReportLimits(ServerPlayer player, ReportLimitsPayload payload) {
        LIMITS.put(player.getUUID(), new int[]{
                Math.max(0, Math.min(200, payload.comfortMax())),
                Math.max(0, Math.min(200, payload.absoluteMax()))
        });
        // 玩家（重新）进入世界时，把服务端记着的控制状态补发给他 —— 否则重连后强度会掉
        if (OWNER_OF.containsKey(player.getUUID())) {
            ServerPlayNetworking.send(player, new RemoteCommandPayload(
                    RemoteCommandPayload.OP_BASE, BASE.getOrDefault(player.getUUID(), 0), 0, 0));
            ServerPlayNetworking.send(player, new RemoteCommandPayload(
                    RemoteCommandPayload.OP_HP, hpEnabledOf(player.getUUID()) ? 1 : 0, 0, 0));
        }
    }

    /** 被控者上报自己的郊狼设备连接状态；同时把最新状态推给主人刷新面板与 HUD。 */
    public static void handleSubjectStatus(ServerPlayer player, int connected) {
        SUBJECT_CONNECTED.put(player.getUUID(), connected != 0);
        if (connected == 0) {
            // 断开就把强度清零，免得主人 HUD 一直停在上一个数字上
            PEER_STRENGTH.remove(player.getUUID());
        }
        UUID ownerId = OWNER_OF.get(player.getUUID());
        if (ownerId == null) {
            return;
        }
        ServerPlayer owner = player.level().getServer().getPlayerList().getPlayer(ownerId);
        if (owner != null) {
            sendPanel(owner, findController(owner));
            sendPeerStrength(owner, player.getUUID());
        }
    }

    /** 被控者上报实时强度：记下来，转发给主人。 */
    public static void handlePeerStrength(ServerPlayer player, PeerStrengthReportPayload payload) {
        PEER_STRENGTH.put(player.getUUID(), new int[]{
                Math.max(0, Math.min(200, payload.a())),
                Math.max(0, Math.min(200, payload.b()))
        });
        UUID ownerId = OWNER_OF.get(player.getUUID());
        if (ownerId == null) {
            return;
        }
        ServerPlayer owner = player.level().getServer().getPlayerList().getPlayer(ownerId);
        if (owner != null) {
            ServerPlayNetworking.send(owner, new PeerStrengthPayload(payload.a(), payload.b()));
        }
    }

    /** 把某个被控者的实时强度推给他的主人（没有数据就发「未连接」）。 */
    private static void sendPeerStrength(ServerPlayer owner, UUID subjectId) {
        int[] v = PEER_STRENGTH.get(subjectId);
        ServerPlayNetworking.send(owner, v == null
                ? PeerStrengthPayload.unknown()
                : new PeerStrengthPayload(v[0], v[1]));
    }

    private static int comfortOf(ServerPlayer player) {
        int[] v = LIMITS.get(player.getUUID());
        return v == null ? DEFAULT_COMFORT : v[0];
    }

    private static int absoluteOf(ServerPlayer player) {
        int[] v = LIMITS.get(player.getUUID());
        return v == null ? DEFAULT_ABSOLUTE : v[1];
    }

    // ------------------------------------------------------------------ 绑定

    /** 主人的被控者（在线且项圈关系仍然成立才返回）。{@code controller} 可以为 null。 */
    public static Optional<ServerPlayer> resolveSubject(ServerPlayer owner, ItemStack controller) {
        UUID subjectId = RELATIONS.get(owner.getUUID());
        if (subjectId == null && controller != null && ControllerData.isController(controller)) {
            subjectId = ControllerData.getSubject(controller).orElse(null);
        }
        if (subjectId == null) {
            return Optional.empty();
        }
        ServerPlayer target = owner.level().getServer().getPlayerList().getPlayer(subjectId);
        if (target == null) {
            return Optional.empty();
        }
        // 关系必须两边都对得上：目标戴着项圈，且项圈上记的主人是自己
        if (!CollarService.isWearingCollar(target) || !CollarService.isCollarOwner(target, owner.getUUID())) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    /**
     * 主人手持控制器右键某个玩家：把对方变成自己的被控者。
     *
     * <p>要求对方已经戴着项圈，且这条项圈还没被别人绑定（或者本来就是自己）。
     */
    public static boolean tryBind(ServerPlayer actor, ServerPlayer target, ItemStack controller) {
        if (actor == target) {
            return bindSelf(actor, controller);
        }
        if (!CollarService.isWearingCollar(target)) {
            actor.sendSystemMessage(Component.literal("§7对方没有戴项圈，先给他戴上。"));
            return false;
        }
        boolean alreadyMine = CollarService.isCollarOwner(target, actor.getUUID());
        // 只有「已经被别人占据」才算抢不走。主人的 UUID 恰好是被戴者自己 = 他自己戴上的，
        // 表示"期待被控制"，任何手持控制器的人都能接管（用户 2026-11-04 要求）。
        boolean ownedByOther = !alreadyMine
                && CollarService.findCollarSlot(target)
                .map(slot -> com.civon.wildestfantasy.collar.CollarData.getOwner(slot.get())
                        .filter(owner -> !owner.equals(target.getUUID()))
                        .isPresent())
                .orElse(false);
        if (ownedByOther) {
            actor.sendSystemMessage(Component.literal("§c这条项圈已经有主人了。"));
            return false;
        }
        // 接管：清掉上一任主人（可能就是他自己的 bindSelf）留下的关系，否则旧面板还显示已绑定
        UUID previous = OWNER_OF.get(target.getUUID());
        if (previous != null) {
            RELATIONS.remove(previous, target.getUUID());
            if (!previous.equals(actor.getUUID())) {
                ServerPlayer prev = actor.level().getServer().getPlayerList().getPlayer(previous);
                if (prev != null) {
                    sendPanel(prev, findController(prev));
                    prev.sendSystemMessage(Component.literal(
                            "§7你对 " + target.getName().getString() + " 的控制已被他人接管。"));
                }
            }
        }
        BASE.remove(target.getUUID());

        if (!CollarService.setCollarOwner(target, actor.getUUID())) {
            return false;
        }
        RELATIONS.put(actor.getUUID(), target.getUUID());
        OWNER_OF.put(target.getUUID(), actor.getUUID());
        // 背包里所有控制器都打上同一个绑定目标，换哪一把都能用
        stampControllers(actor, target.getUUID(), target.getName().getString());
        // 刚绑上时先把对方当前状态推过去，HUD 不会空着
        sendPeerStrength(actor, target.getUUID());
        actor.sendSystemMessage(Component.literal("§5已绑定 §f" + target.getName().getString() + "§5。"));
        target.sendSystemMessage(Component.literal("§7你的主人是 §f" + actor.getName().getString() + "§7。"));
        return true;
    }

    /** 单人模式：自己控自己，项圈主人记成自己。 */
    public static boolean bindSelf(ServerPlayer actor, ItemStack controller) {
        if (!CollarService.isWearingCollar(actor)) {
            actor.sendSystemMessage(Component.literal("§7你还没有戴项圈。"));
            return false;
        }
        if (!CollarService.setCollarOwner(actor, actor.getUUID())) {
            return false;
        }
        RELATIONS.put(actor.getUUID(), actor.getUUID());
        OWNER_OF.put(actor.getUUID(), actor.getUUID());
        stampControllers(actor, actor.getUUID(), actor.getName().getString());
        actor.sendSystemMessage(Component.literal("§5已绑定自己。"));
        return true;
    }

    public static void unbind(ServerPlayer actor, ItemStack controller) {
        UUID subject = RELATIONS.remove(actor.getUUID());
        if (subject != null) {
            OWNER_OF.remove(subject, actor.getUUID());
            BASE.remove(subject);
            // 解绑之后项圈主人回到被控者自己，他就可以自己摘、自己输入指令了
            ServerPlayer target = actor.level().getServer().getPlayerList().getPlayer(subject);
            if (target != null && CollarService.isWearingCollar(target)) {
                CollarService.setCollarOwner(target, subject);
            }
        }
        clearControllerStamps(actor);
        // 关系没了，主人那边的「对方」强度块清空
        ServerPlayNetworking.send(actor, PeerStrengthPayload.unknown());
        actor.sendSystemMessage(Component.literal("§7已解除绑定。"));
    }

    // ------------------------------------------------------------------ 指令

    public static void handleControlAction(ServerPlayer sender, ControlActionPayload payload) {
        ItemStack controller = findController(sender);
        if (payload.op() == ControlActionPayload.OP_QUERY) {
            // 查询不要求控制器：按快捷键打开面板时背包里可能一把都没有
            sendPanel(sender, controller);
            return;
        }
        if (controller.isEmpty()) {
            sender.sendSystemMessage(Component.literal("§7你没有郊狼控制器。"));
            return;
        }
        if (payload.op() == ControlActionPayload.OP_UNBIND) {
            unbind(sender, controller);
            sendPanel(sender, controller);
            return;
        }
        if (payload.op() == ControlActionPayload.OP_BIND_SELF) {
            bindSelf(sender, controller);
            sendPanel(sender, controller);
            return;
        }

        Optional<ServerPlayer> targetOpt = resolveSubject(sender, controller);
        if (targetOpt.isEmpty()) {
            sender.sendSystemMessage(Component.literal(whyNoSubject(sender, controller)));
            sendPanel(sender, controller);
            return;
        }
        ServerPlayer target = targetOpt.get();

        switch (payload.op()) {
            case ControlActionPayload.OP_BASE -> {
                int value = Math.max(0, Math.min(Math.min(comfortOf(target), absoluteOf(target)), payload.a()));
                BASE.put(target.getUUID(), value);
                ServerPlayNetworking.send(target, new RemoteCommandPayload(
                        RemoteCommandPayload.OP_BASE, value, 0, 0));
            }
            case ControlActionPayload.OP_PUNISH -> {
                int strength = Math.max(0, Math.min(absoluteOf(target), payload.a()));
                int duration = Math.max(0, Math.min(60_000, payload.b()));
                ServerPlayNetworking.send(target, new RemoteCommandPayload(
                        RemoteCommandPayload.OP_PUNISH, strength, duration, 0));
            }
            case ControlActionPayload.OP_EFFECT ->
                    applyEffect(target, payload.a(), payload.b(), payload.c());
            case ControlActionPayload.OP_CLEAR -> {
                BASE.remove(target.getUUID());
                ServerPlayNetworking.send(target, new RemoteCommandPayload(
                        RemoteCommandPayload.OP_CLEAR, 0, 0, 0));
            }
            case ControlActionPayload.OP_HP_TOGGLE -> {
                boolean next = !hpEnabledOf(target.getUUID());
                HP_ENABLED.put(target.getUUID(), next);
                ServerPlayNetworking.send(target, new RemoteCommandPayload(
                        RemoteCommandPayload.OP_HP, next ? 1 : 0, 0, 0));
                sender.sendSystemMessage(Component.literal(
                        "§7目标的血量映射已" + (next ? "§a开启" : "§c关闭") + "§7。"));
            }
            default -> {
                return;
            }
        }
        sendPanel(sender, controller);
    }

    // ------------------------------------------------------------------ 状态效果

    /** 面板上可选的 8 种效果，序号与客户端面板一致。 */
    public static final String[] EFFECT_NAMES = {
            "缓慢", "失明", "虚弱", "饥饿", "漂浮", "缓降", "生命回复", "抗性提升"
    };

    /**
     * 施加状态效果。<b>效果必须由服务端施加</b> —— 客户端 {@code addEffect} 只是本地视觉，
     * 服务端不认，怪物和其他玩家也看不到。
     */
    private static void applyEffect(ServerPlayer target, int index, int amplifier, int seconds) {
        if (index < 0 || index >= EFFECT_NAMES.length) {
            return;
        }
        Holder<MobEffect> effect = switch (index) {
            case 0 -> MobEffects.SLOWNESS;
            case 1 -> MobEffects.BLINDNESS;
            case 2 -> MobEffects.WEAKNESS;
            case 3 -> MobEffects.HUNGER;
            case 4 -> MobEffects.LEVITATION;
            case 5 -> MobEffects.SLOW_FALLING;
            case 6 -> MobEffects.REGENERATION;
            default -> MobEffects.RESISTANCE;
        };
        int ticks = Math.max(3, Math.min(60, seconds)) * 20;
        target.addEffect(new MobEffectInstance(effect, ticks, Math.max(0, amplifier)));
        target.sendSystemMessage(Component.literal(
                "§7你被施加了 §f" + EFFECT_NAMES[index] + "§7（" + seconds + " 秒）"));
    }

    private static void sendPanel(ServerPlayer owner, ItemStack controller) {
        Optional<ServerPlayer> targetOpt = resolveSubject(owner, controller);
        if (targetOpt.isEmpty()) {
            ServerPlayNetworking.send(owner, new PanelStatePayload(
                    0, DEFAULT_COMFORT, DEFAULT_ABSOLUTE, 0, 1, "", 0));
            return;
        }
        ServerPlayer target = targetOpt.get();
        ServerPlayNetworking.send(owner, new PanelStatePayload(
                1,
                comfortOf(target),
                absoluteOf(target),
                BASE.getOrDefault(target.getUUID(), 0),
                hpEnabledOf(target.getUUID()) ? 1 : 0,
                target.getName().getString(),
                subjectConnectedOf(target.getUUID()) ? 1 : 0
        ));
    }

    /** 血量映射开关：默认开启。 */
    private static boolean hpEnabledOf(UUID subject) {
        return HP_ENABLED.getOrDefault(subject, Boolean.TRUE);
    }

    /** 被控者的郊狼设备是否已连接。 */
    private static boolean subjectConnectedOf(UUID subject) {
        return SUBJECT_CONNECTED.getOrDefault(subject, Boolean.FALSE);
    }

    /**
     * 背包里（含副手、快捷栏）任意一把郊狼控制器。
     *
     * <p>用户要求：绑定之后只要背包里有控制器就能用控制面板，不要求拿在手上；
     * 背包里有多把时每一把都等价（绑定时会把这些控制器全部打上同一个绑定标记）。
     */
    public static ItemStack findController(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (ControllerData.isController(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 把绑定关系写进背包里的<strong>所有</strong>控制器，这样换哪一把都一样能控。 */
    private static void stampControllers(ServerPlayer owner, UUID subject, String name) {
        var inventory = owner.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (ControllerData.isController(stack)) {
                ControllerData.bind(stack, subject, name);
            }
        }
    }

    /** 解绑时清掉背包里所有控制器的绑定标记。 */
    private static void clearControllerStamps(ServerPlayer owner) {
        var inventory = owner.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (ControllerData.isController(stack)) {
                ControllerData.unbind(stack);
            }
        }
    }

    /** 找不到被控者时给一句准确的原因（不要只说「没绑定」。） */
    private static String whyNoSubject(ServerPlayer owner, ItemStack controller) {
        UUID subjectId = RELATIONS.get(owner.getUUID());
        if (subjectId == null && controller != null && !controller.isEmpty()
                && ControllerData.isController(controller)) {
            subjectId = ControllerData.getSubject(controller).orElse(null);
        }
        if (subjectId == null) {
            return "§7还没有绑定目标。";
        }
        ServerPlayer target = owner.level().getServer().getPlayerList().getPlayer(subjectId);
        if (target == null) {
            return "§7目标不在线。";
        }
        if (!CollarService.isWearingCollar(target)) {
            return "§7目标没有戴项圈。";
        }
        return "§7这条项圈的主人不是你。";
    }

    /**
     * 玩家下线时的收尾。
     *
     * <p>主人下线 -> <b>解除关系</b>（用户要求不保留，需要重新绑定），并让被控者立刻归零；
     * 被控者下线 -> 只清运行态，关系留着，主人重新登录还能看到「设备未连接」。
     */
    public static void onPlayerGone(ServerPlayer player) {
        LIMITS.remove(player.getUUID());
        SUBJECT_CONNECTED.remove(player.getUUID());
        PEER_STRENGTH.remove(player.getUUID());

        UUID subject = RELATIONS.remove(player.getUUID());
        if (subject != null) {
            OWNER_OF.remove(subject, player.getUUID());
            BASE.remove(subject);
            ServerPlayer target = player.level().getServer().getPlayerList().getPlayer(subject);
            if (target != null) {
                ServerPlayNetworking.send(target, new RemoteCommandPayload(
                        RemoteCommandPayload.OP_CLEAR, 0, 0, 0));
                ServerPlayNetworking.send(target, new RemoteCommandPayload(
                        RemoteCommandPayload.OP_HP, 1, 0, 0));
                if (CollarService.isWearingCollar(target)) {
                    // 项圈主人回到被控者自己，他可以自己摘、自己用指令了
                    CollarService.setCollarOwner(target, subject);
                }
                target.sendSystemMessage(Component.literal("§7你的主人已下线，控制关系解除。"));
            }
        }

        UUID owner = OWNER_OF.remove(player.getUUID());
        if (owner != null) {
            RELATIONS.remove(owner, player.getUUID());
            BASE.remove(player.getUUID());
            ServerPlayer ownerPlayer = player.level().getServer().getPlayerList().getPlayer(owner);
            if (ownerPlayer != null) {
                sendPanel(ownerPlayer, findController(ownerPlayer));
                ServerPlayNetworking.send(ownerPlayer, PeerStrengthPayload.unknown());
            }
        }
    }
}
