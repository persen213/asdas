package io.blackhole.addon;

import io.github.pinchan4273.reacademycraft.api.AbilityId;
import io.github.pinchan4273.reacademycraft.api.ActivationKind;
import io.github.pinchan4273.reacademycraft.api.CategoryDefinition;
import io.github.pinchan4273.reacademycraft.api.Presentation;
import io.github.pinchan4273.reacademycraft.api.SkillDefinition;
import io.github.pinchan4273.reacademycraft.api.TreePosition;
import io.github.pinchan4273.reacademycraft.api.forge.RegisterAbilitiesEvent;
import io.github.pinchan4273.reacademycraft.api.mc.AcademySafety;
import io.github.pinchan4273.reacademycraft.api.mc.ActivationContext;
import io.github.pinchan4273.reacademycraft.api.mc.SkillBehavior;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Black Hole Add-on for Re:AcademyCraft 1.5.0
 * Single skill: Black Hole (TOGGLE)
 */
@Mod(BlackHoleAddon.MOD_ID)
public final class BlackHoleAddon {
    public static final String MOD_ID = "blackhole";
    static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final RegistryObject<Item> BLACKHOLE_FACTOR = ITEMS.register("blackhole_factor",
        () -> new Item(new Item.Properties().stacksTo(16)));

    static AbilityId id(String path) { return AbilityId.parse(MOD_ID + ":" + path); }

    // Category
    static final AbilityId BLACKHOLE_CATEGORY = id("blackhole");

    // Single skill
    static final AbilityId BLACK_HOLE = id("black_hole");

    public BlackHoleAddon() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        bus.addListener(this::registerAbilities);
        bus.addListener(this::creativeTab);
    }

    private void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) event.accept(BLACKHOLE_FACTOR);
    }

    private static Presentation shown(AbilityId skill) {
        return new Presentation(MOD_ID + ".skill." + skill.path(), MOD_ID + ".skill." + skill.path() + ".desc", null);
    }

    private static Presentation catShown() {
        return new Presentation(MOD_ID + ".ability.blackhole", null, null);
    }

    private void registerAbilities(RegisterAbilitiesEvent event) {
        var abilities = event.registrar(MOD_ID);

        // Category
        abilities.category(new CategoryDefinition(
            BLACKHOLE_CATEGORY,
            id("blackhole_factor"),
            List.of(1, 1, 1, 1, 1),
            catShown()
        ));

        // Black Hole skill (level 5, TOGGLE)
        abilities.skill(new SkillDefinition(
            BLACK_HOLE,
            BLACKHOLE_CATEGORY,
            5,
            List.of(),
            ActivationKind.TOGGLE,
            shown(BLACK_HOLE),
            new TreePosition(40, 50, null)
        ));

        // Behavior
        event.behaviors(MOD_ID).behavior(BLACK_HOLE, new BlackHoleBehavior());
    }

    /**
     * Black Hole (TOGGLE): Creates a black hole at aim position.
     * Grows for 1s, pulls entities in radius for 5s, then collapses with damage + knockback.
     * Press again to collapse early.
     */
    private static class BlackHoleBehavior implements SkillBehavior {
        private static final ConcurrentHashMap<UUID, BlackHoleState> ACTIVE_HOLES = new ConcurrentHashMap<>();

        private static class BlackHoleState {
            Vec3 center;
            float radius = 0f;
            float maxRadius;
            int phase = 0; // 0=growing, 1=active, 2=collapsing
            int age = 0;
            int growDuration;
            int activeDuration;
            long groupId;
        }

        @Override
        public io.github.pinchan4273.reacademycraft.api.ActivationOutcome start(ActivationContext ctx) {
            var actor = ctx.actor();
            UUID owner = actor.id();

            BlackHoleState existing = ACTIVE_HOLES.get(owner);
            if (existing != null) {
                // Second press = early collapse
                existing.phase = 2; // collapsing
                return io.github.pinchan4273.reacademycraft.api.ActivationOutcome.STARTED;
            }

            // First press: create black hole
            if (!ctx.pay(80, 20)) {
                return io.github.pinchan4273.reacademycraft.api.ActivationOutcome.REFUSED_RESOURCE;
            }

            ServerLevel level = actor.level();
            Vec3 eye = actor.position();
            Vec3 look = actor.look();

            var clip = level.clip(new ClipContext(eye, eye.add(look.scale(28)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor.entity()));
            Vec3 center = clip.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                ? eye.add(look.scale(28)) : clip.getLocation();

            BlackHoleState state = new BlackHoleState();
            state.center = center;
            state.maxRadius = 12f;
            state.growDuration = 20; // 1 second
            state.activeDuration = 100; // 5 seconds
            state.groupId = System.nanoTime();
            ACTIVE_HOLES.put(owner, state);

            // Cooldown from end of effect (grow + active + collapse)
            ctx.setCooldown(20 * 10 + 20 + 100 + 20);

            return io.github.pinchan4273.reacademycraft.api.ActivationOutcome.STARTED;
        }

        @Override
        public boolean tick(ActivationContext ctx) {
            var actor = ctx.actor();
            UUID owner = actor.id();
            BlackHoleState state = ACTIVE_HOLES.get(owner);

            if (state == null) return false;

            state.age++;

            ServerLevel level = actor.level();

            if (state.phase == 0) {
                // Growing phase
                float progress = (float) state.age / state.growDuration;
                state.radius = state.maxRadius * progress;
                if (state.age >= state.growDuration) {
                    state.phase = 1;
                    state.age = 0;
                }
            } else if (state.phase == 1) {
                // Active phase - pull entities
                pullEntities(level, state);

                if (state.age >= state.activeDuration) {
                    state.phase = 2;
                    state.age = 0;
                }
            } else if (state.phase == 2) {
                // Collapsing phase
                if (state.age == 1) {
                    // Damage and knockback at center
                    collapse(level, state);
                }
                float progress = (float) state.age / 10; // 10 ticks collapse
                state.radius = state.maxRadius * (1f - progress);
                if (state.age >= 10) {
                    ACTIVE_HOLES.remove(owner);
                    return false; // End session
                }
            }

            return true;
        }

        @Override
        public void stop(ActivationContext ctx, io.github.pinchan4273.reacademycraft.api.StopReason reason) {
            ACTIVE_HOLES.remove(ctx.actor().id());
        }

        private void pullEntities(ServerLevel level, BlackHoleState state) {
            AABB box = new AABB(state.center.x - state.radius, state.center.y - state.radius, state.center.z - state.radius,
                                state.center.x + state.radius, state.center.y + state.radius, state.center.z + state.radius);

            List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive());

            for (LivingEntity target : targets) {
                Vec3 toCenter = state.center.subtract(target.position());
                double dist = toCenter.length();
                if (dist < 0.5) continue;

                // Pull strength increases closer to center
                double pullStrength = Math.min(0.8, state.radius / (dist + 1) * 0.3);
                Vec3 pull = toCenter.normalize().scale(pullStrength);
                target.setDeltaMovement(target.getDeltaMovement().add(pull));
                target.hurtMarked = true;
                target.fallDistance = 0;
            }
        }

        private void collapse(ServerLevel level, BlackHoleState state) {
            AABB box = new AABB(state.center.x - 4, state.center.y - 4, state.center.z - 4,
                                state.center.x + 4, state.center.y + 4, state.center.z + 4);

            List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive());

            // Create a temporary actor for AcademySafety
            var tempActor = new io.github.pinchan4273.reacademycraft.api.mc.AbilityActor() {
                @Override public UUID id() { return java.util.UUID.randomUUID(); }
                @Override public io.github.pinchan4273.reacademycraft.api.ActorKind kind() { return io.github.pinchan4273.reacademycraft.api.ActorKind.NATURAL; }
                @Override public ServerLevel level() { return level; }
                @Override public Vec3 position() { return state.center; }
                @Override public Vec3 look() { return Vec3.ZERO; }
                @Override public net.minecraft.world.entity.Entity entity() { return null; }
                @Override public net.minecraft.server.level.ServerPlayer attributedPlayer() { return null; }
            };

            for (LivingEntity target : targets) {
                if (AcademySafety.mayAttack(tempActor, target, BlackHoleAddon.BLACK_HOLE)) {
                    AcademySafety.attack(tempActor, target, BlackHoleAddon.BLACK_HOLE, 15f);
                    Vec3 knockback = target.position().subtract(state.center).normalize().scale(1.5);
                    target.push(knockback.x, 0.5, knockback.z);
                    target.hurtMarked = true;
                }
            }
        }
    }
}