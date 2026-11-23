package com.simone.icam.core;

/**
 * A rendering style. Face brightness and skin colour are adapted per photo towards the targets,
 * so the result does not depend on how the scene came out of the sensor.
 * Skin targets are "natural" values (R/G ~1.38, B/G ~0.85), measured on the user's own
 * stock-camera selfie, not on a sunburnt reference.
 */
public final class Look {
    public final String name;
    /** < 1 lifts shadows (HDR); 1 = no local lift. */
    public float localContrast;
    /** true: bright background is allowed to go white (high-key). */
    public boolean highKey;
    /** Target mean luma (0..255, display) of the inner face area; 0 = off. */
    public float faceTarget;
    /** Target skin chromaticity R/G and B/G; 0 = off. */
    public float skinRG, skinBG;
    /** 0..1 how strongly skin is pulled to the target colour. */
    public float skinStrength;
    /** 0..1 how strongly near-white areas are neutralised (auto white balance correction). */
    public float wbStrength;
    /** Lift a dark face in backlit scenes. */
    public boolean backlitLift;
    /** Linear level that becomes pure white (1 = none). Lower = brighter, whiter background. */
    public float whitePoint = 1f;
    public ColorLut lut;

    private Look(String name) { this.name = name; }

    /** iPhone 12-14 style selfie: Smart-HDR-like tone, face well exposed, natural skin, neutral-warm whites. */
    public static Look iphone() {
        Look l = new Look("iPhone");
        l.localContrast = 0.75f;
        l.highKey = false;
        l.backlitLift = true;
        l.faceTarget = 112f;
        l.skinRG = 1.38f;
        l.skinBG = 0.85f;
        l.skinStrength = 0.85f;
        l.wbStrength = 0.5f;
        l.lut = ColorLut.look(33, 0.3f, 1.02f, 1.04f, 0.95f, 0f);
        return l;
    }

    /** Bright high-key selfie (white background), natural skin. */
    public static Look luminoso() {
        Look l = new Look("Luminoso");
        l.localContrast = 1.0f;
        l.highKey = true;
        l.faceTarget = 115f;
        l.skinRG = 1.38f;
        l.skinBG = 0.85f;
        l.skinStrength = 0.85f;
        l.wbStrength = 0.5f;
        l.whitePoint = 0.75f;
        l.lut = ColorLut.look(33, 0.3f, 1.03f, 1.05f, 0.95f, 0.5f);
        return l;
    }

    /** Warm evening interior: ambient light kept warm, no clipped highlights. */
    public static Look sera() {
        Look l = new Look("Sera calda");
        l.localContrast = 0.9f;
        l.highKey = false;
        l.faceTarget = 100f;
        l.skinRG = 1.42f;
        l.skinBG = 0.84f;
        l.skinStrength = 0.5f;
        l.wbStrength = 0f;
        l.lut = ColorLut.look(33, 1.0f, 1.06f, 1.06f, 0.97f, 0f);
        return l;
    }

    /** Kept for tests: neutral rendering without face/skin adaptation. */
    public static Look natural() {
        Look l = new Look("Naturale");
        l.localContrast = 0.72f;
        l.backlitLift = true;
        l.lut = ColorLut.appleLike(33, 0.6f, 1f);
        return l;
    }
}
