package com.qwarwin.fpvdrones.client.screen;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.crafting.AssemblyRecipe;
import com.qwarwin.fpvdrones.inventory.AssemblyContainer;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Optional;

@OnlyIn(Dist.CLIENT)
public class AssemblyScreen extends ContainerScreen<AssemblyContainer> {
    private static final ResourceLocation TEXTURE = FpvDrones.id("textures/gui/assembly_bench.png");

    public AssemblyScreen(AssemblyContainer menu, PlayerInventory inv, ITextComponent title) {
        super(menu, inv, title);
        imageWidth = 176;
        imageHeight = 166;
        titleLabelX = 29;
    }

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partial) {
        renderBackground(ms);
        super.render(ms, mouseX, mouseY, partial);
        renderTooltip(ms, mouseX, mouseY);
    }

    @Override
    protected void renderBg(MatrixStack ms, float partial, int mouseX, int mouseY) {
        RenderSystem.color4f(1F, 1F, 1F, 1F);
        if (minecraft == null) {
            return;
        }
        minecraft.getTextureManager().bind(TEXTURE);
        blit(ms, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }

    @Override
    protected void renderLabels(MatrixStack ms, int mouseX, int mouseY) {
        super.renderLabels(ms, mouseX, mouseY);
        if (minecraft == null || minecraft.level == null || menu.getSlot(AssemblyContainer.RESULT).hasItem()) {
            return;
        }
        Optional<AssemblyRecipe> recipe = menu.findRecipe(minecraft.level);
        if (recipe.isPresent() && recipe.get().needsSoldering() && !menu.hasIron()) {
            ITextComponent hint = new TranslationTextComponent("screen.fpvdrones.needs_iron");
            font.draw(ms, hint, 170 - font.width(hint), 20, 0xC03030);
        }
    }
}
