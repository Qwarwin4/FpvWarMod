package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.registry.ModItems;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;

// ordinal goes into NBT and entity data, append only
public enum PayloadType {
    NONE("none", false, false, 0F),
    PG7VS("pg7vs", true, false, 0.13F),
    OG7("og7", true, false, 0.12F),
    CANISTER("explosive_canister", true, false, 0.17F),
    RGD5("rgd5", false, true, 0.17F),
    VOG17M("vog17m", false, true, 0.21F),
    RADIO_BEACON("radio_beacon", false, true, 0.12F);

    private final String name;
    private final boolean fpv;
    private final boolean mavic;
    // how far it hangs below the frame, in blocks
    private final float hang;

    PayloadType(String name, boolean fpv, boolean mavic, float hang) {
        this.name = name;
        this.fpv = fpv;
        this.mavic = mavic;
        this.hang = hang;
    }

    public String getName() {
        return name;
    }

    public boolean fitsFpv() {
        return fpv;
    }

    public boolean fitsMavic() {
        return mavic;
    }

    public float getHang() {
        return hang;
    }

    public ResourceLocation model() {
        return FpvDrones.id("entity/" + name);
    }

    public String translationKey() {
        return "item." + FpvDrones.MODID + "." + name;
    }

    public boolean isExplosive() {
        return this != NONE && this != RADIO_BEACON;
    }

    public float power() {
        switch (this) {
            case PG7VS:
                return Config.POWER_PG7VS.get().floatValue();
            case OG7:
                return Config.POWER_OG7.get().floatValue();
            case CANISTER:
                return Config.POWER_CANISTER.get().floatValue();
            case RGD5:
                return Config.POWER_RGD5.get().floatValue();
            case VOG17M:
                return Config.POWER_VOG17M.get().floatValue();
            default:
                return 0F;
        }
    }

    public Item item() {
        switch (this) {
            case PG7VS:
                return ModItems.PG7VS.get();
            case OG7:
                return ModItems.OG7.get();
            case CANISTER:
                return ModItems.CANISTER.get();
            case RGD5:
                return ModItems.RGD5.get();
            case VOG17M:
                return ModItems.VOG17M.get();
            case RADIO_BEACON:
                return ModItems.RADIO_BEACON.get();
            default:
                return Items.AIR;
        }
    }

    public static PayloadType byId(int id) {
        PayloadType[] v = values();
        return id >= 0 && id < v.length ? v[id] : NONE;
    }

    public static PayloadType fromItem(Item item) {
        return item instanceof PayloadItem ? ((PayloadItem) item).getType() : NONE;
    }
}
