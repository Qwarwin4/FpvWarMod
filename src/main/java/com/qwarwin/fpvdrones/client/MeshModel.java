package com.qwarwin.fpvdrones.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Matrix3f;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Real 3D models (models/entity/NAME.mesh, written by tools/gen_meshes.py from the sources listed in MODELS.md):
 * triangles with their own textures, in the same frame as the JSON model of the same name (block units, the JSON's
 * (8,8,8) at (0.5,0.5,0.5)), so every renderer that drew the JSON draws the mesh in its place.
 * gzip "MTMS", int version 2, int sections, then per section: UTF texture, int triangles, 3 x 8 floats (x y z u v nx ny nz).
 */
@OnlyIn(Dist.CLIENT)
public final class MeshModel {
    private static final Logger LOG = LogManager.getLogger();
    private static final Map<ResourceLocation, MeshModel> CACHE = new HashMap<>();
    private static final MeshModel NONE = new MeshModel(new ResourceLocation[0], new float[0][]);

    private final ResourceLocation[] textures;
    private final float[][] tris;
    private final boolean[] glass;

    private MeshModel(ResourceLocation[] textures, float[][] tris) {
        this.textures = textures;
        this.tris = tris;
        this.glass = new boolean[textures.length];
        for (int i = 0; i < textures.length; i++) {
            String p = textures[i].getPath();
            glass[i] = p.substring(p.lastIndexOf('/') + 1).startsWith("glass");
        }
    }

    /** The mesh for a model id like fpvdrones:entity/fpv_drone, or null when it only has its JSON. */
    @Nullable
    public static MeshModel get(ResourceLocation model) {
        MeshModel m = CACHE.computeIfAbsent(model, MeshModel::load);
        return m == NONE ? null : m;
    }

    /** Forget loaded meshes (resource reload). */
    public static void clear() {
        CACHE.clear();
    }

    private static MeshModel load(ResourceLocation model) {
        ResourceLocation loc = new ResourceLocation(model.getNamespace(), "models/" + model.getPath() + ".mesh");
        if (!Minecraft.getInstance().getResourceManager().hasResource(loc)) {
            return NONE;
        }
        try (IResource res = Minecraft.getInstance().getResourceManager().getResource(loc);
             DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(res.getInputStream())))) {
            return read(in);
        } catch (Exception e) {
            LOG.warn("FPV Drones: can't load mesh {}: {}", loc, e.toString());
            return NONE;
        }
    }

    private static MeshModel read(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != 'M' || magic[1] != 'T' || magic[2] != 'M' || magic[3] != 'S') {
            throw new IOException("not a mesh");
        }
        int version = in.readInt();
        int sections = version >= 2 ? in.readInt() : 1;
        ResourceLocation[] tex = new ResourceLocation[sections];
        float[][] tris = new float[sections][];
        for (int s = 0; s < sections; s++) {
            ResourceLocation t = new ResourceLocation(in.readUTF());
            tex[s] = new ResourceLocation(t.getNamespace(), "textures/" + t.getPath() + ".png");
            int n = in.readInt();
            float[] f = new float[n * 24];
            for (int i = 0; i < f.length; i++) {
                f[i] = in.readFloat();
            }
            tris[s] = f;
        }
        return new MeshModel(tex, tris);
    }

    public void render(MatrixStack ms, IRenderTypeBuffer buf, int light) {
        render(ms, buf, light, 1F, 1F, 1F);
    }

    public void render(MatrixStack ms, IRenderTypeBuffer buf, int light, float r, float g, float b) {
        Matrix4f pose = ms.last().pose();
        Matrix3f normal = ms.last().normal();
        // entity render types take quads: each triangle goes out with its last corner doubled
        for (int s = 0; s < tris.length; s++) {
            IVertexBuilder vb = buf.getBuffer(glass[s] ? RenderType.entityTranslucent(textures[s])
                    : RenderType.entityCutoutNoCull(textures[s]));
            float[] t = tris[s];
            for (int o = 0; o < t.length; o += 24) {
                for (int i = 0; i < 4; i++) {
                    int c = o + Math.min(i, 2) * 8;
                    vb.vertex(pose, t[c], t[c + 1], t[c + 2]).color(r, g, b, 1F).uv(t[c + 3], t[c + 4])
                            .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(normal, t[c + 5], t[c + 6], t[c + 7])
                            .endVertex();
                }
            }
        }
    }
}
