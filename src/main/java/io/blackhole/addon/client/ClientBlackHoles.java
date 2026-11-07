package io.blackhole.addon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "blackhole", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientBlackHoles {
    private static final Map<Long, BlackHoleEffect> ACTIVE_HOLES = new ConcurrentHashMap<>();

    public static void init() {}

    public static void spawn(Vec3 center, float maxRadius, long groupId) {
        ACTIVE_HOLES.put(groupId, new BlackHoleEffect(center, maxRadius));
    }

    public static void end(long groupId) {
        BlackHoleEffect effect = ACTIVE_HOLES.remove(groupId);
        if (effect != null) {
            effect.end();
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();

        var frustum = event.getFrustum();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

        for (BlackHoleEffect effect : ACTIVE_HOLES.values()) {
            if (effect.isExpired()) {
                ACTIVE_HOLES.remove(effect.groupId());
                continue;
            }

            AABB bounds = effect.getBoundingBox();
            if (bounds != null && !frustum.isVisible(bounds)) continue;

            effect.render(poseStack, bufferSource, cameraPos, event.getPartialTick());
        }
    }

    public static void clearAll() {
        for (BlackHoleEffect effect : ACTIVE_HOLES.values()) {
            effect.end();
        }
        ACTIVE_HOLES.clear();
    }

    public static class BlackHoleEffect {
        private final Vec3 center;
        private final float maxRadius;
        private final long groupId;
        private int age = 0;
        private float radius = 0f;
        private int phase = 0; // 0=growing, 1=active, 2=collapsing
        private float openProgress = 0f;

        public BlackHoleEffect(Vec3 center, float maxRadius) {
            this.center = center;
            this.maxRadius = maxRadius;
            this.groupId = System.nanoTime();
        }

        public long groupId() { return groupId; }

        public boolean isExpired() {
            return age > 130 + 10; // grow(20) + active(100) + collapse(10) + fade(10)
        }

        public AABB getBoundingBox() {
            float r = maxRadius + 2;
            return new AABB(center.x - r, center.y - r, center.z - r,
                            center.x + r, center.y + r, center.z + r);
        }

        public void end() {
            phase = 2;
        }

        public void render(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 cameraPos, float partialTick) {
            age++;

            // Phase transitions
            if (phase == 0) {
                float progress = Math.min(1f, age / 20f);
                radius = maxRadius * progress;
                openProgress = progress;
                if (age >= 20) {
                    phase = 1;
                }
            } else if (phase == 1) {
                if (age > 120) { // 20 + 100
                    phase = 2;
                }
            } else if (phase == 2) {
                float progress = Math.min(1f, (age - 120) / 10f);
                radius = maxRadius * (1f - progress);
                openProgress = 1f - progress;
            }

            if (radius <= 0) return;

            // Additive glow rendering
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(RenderSystem.SrcFactor.SRC_ALPHA, RenderSystem.DstFactor.ONE);
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            try {
                poseStack.pushPose();
                poseStack.translate(center.x - cameraPos.x, center.y - cameraPos.y, center.z - cameraPos.z);

                // Black core sphere
                renderSphere(poseStack, bufferSource, 0.5f, 0f, 0f, 0f, 0.9f);

                // Purple-blue glow layers
                for (int i = 0; i < 3; i++) {
                    float layerRadius = radius * (0.3f + i * 0.25f);
                    float alpha = 0.15f * (1f - i * 0.25f);
                    renderSphere(poseStack, bufferSource, layerRadius,
                        0.22f, 0.45f, 1.0f, alpha);
                }

                // Outer swirling ring
                renderRing(poseStack, bufferSource, radius, age + partialTick);

                poseStack.popPose();
            } finally {
                RenderSystem.depthMask(true);
                RenderSystem.enableCull();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableBlend();
            }
        }

        private void renderSphere(PoseStack poseStack, MultiBufferSource bufferSource, float radius,
                                  float r, float g, float b, float a) {
            var buffer = bufferSource.getBuffer(RenderType.translucent());
            Matrix4f mat = poseStack.last().pose();

            int segments = 16;
            int rings = 8;
            for (int ri = 0; ri < rings; ri++) {
                float phi1 = (float) Math.PI * ri / rings;
                float phi2 = (float) Math.PI * (ri + 1) / rings;
                float y1 = (float) Math.cos(phi1) * radius;
                float y2 = (float) Math.cos(phi2) * radius;
                float r1 = (float) Math.sin(phi1) * radius;
                float r2 = (float) Math.sin(phi2) * radius;

                for (int si = 0; si < segments; si++) {
                    float theta1 = (float) (2 * Math.PI * si / segments);
                    float theta2 = (float) (2 * Math.PI * (si + 1) / segments);
                    float x11 = (float) Math.cos(theta1) * r1;
                    float z11 = (float) Math.sin(theta1) * r1;
                    float x12 = (float) Math.cos(theta2) * r1;
                    float z12 = (float) Math.sin(theta2) * r1;
                    float x21 = (float) Math.cos(theta1) * r2;
                    float z21 = (float) Math.sin(theta1) * r2;
                    float x22 = (float) Math.cos(theta2) * r2;
                    float z22 = (float) Math.sin(theta2) * r2;

                    buffer.vertex(mat, x11, y1, z11).color(r, g, b, a).endVertex();
                    buffer.vertex(mat, x12, y1, z12).color(r, g, b, a).endVertex();
                    buffer.vertex(mat, x22, y2, z22).color(r, g, b, a).endVertex();
                    buffer.vertex(mat, x21, y2, z21).color(r, g, b, a).endVertex();
                }
            }
        }

        private void renderRing(PoseStack poseStack, MultiBufferSource bufferSource, float radius, float time) {
            var buffer = bufferSource.getBuffer(RenderType.lines());
            Matrix4f mat = poseStack.last().pose();

            int segments = 64;
            for (int i = 0; i < segments; i++) {
                double a1 = 2 * Math.PI * i / segments;
                double a2 = 2 * Math.PI * (i + 1) / segments;
                float x1 = (float) (Math.cos(a1) * radius);
                float z1 = (float) (Math.sin(a1) * radius);
                float x2 = (float) (Math.cos(a2) * radius);
                float z2 = (float) (Math.sin(a2) * radius);

                // Rotate over time
                float rot = time * 10f;
                float cos = (float) Math.cos(rot);
                float sin = (float) Math.sin(rot);
                float rx1 = x1 * cos - z1 * sin;
                float rz1 = x1 * sin + z1 * cos;
                float rx2 = x2 * cos - z2 * sin;
                float rz2 = x2 * sin + z2 * cos;

                // Dashed
                if (i % 4 == 0) {
                    float alpha = 0.3f + 0.2f * (float) Math.sin(time * 5 + i);
                    buffer.vertex(mat, rx1, 0, rz1).color(0.45f, 0.82f, 1.0f, alpha).endVertex();
                    buffer.vertex(mat, rx2, 0, rz2).color(0.45f, 0.82f, 1.0f, alpha).endVertex();
                }
            }
        }
    }
}