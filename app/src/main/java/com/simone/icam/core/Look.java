package com.simone.icam.core;

/**
 * A rendering style. The numeric targets of the "luminoso" and "sera" looks were measured on
 * reference photos (face brightness and skin colour over the inner 70% of the face box).
 * The finisher adapts each photo to reach those targets, so the result does not depend on how
 * the scene came out of the sensor.
 */
public final class Look {
    public final String name;
    /** < 1 lifts shadows (HDR); 1 = no local lift. */
    public float localContrast;
    /** true: bright background is allowed to go white (high-key), face exposure has priority. */
    public boolean highKey;
    /** Target mean luma (0..255, display) of the inner face area; 0 = off. */
    public float faceTarget;
    /** Target skin chromaticity R/G and B/G; 0 = off. */
    public float skinRG, skinBG;
    /** 0..1 how strongly skin is pulled to the target colour. */
    public float skinStrength;
    /** Lift a dark face in backlit scenes (used by the natural look). */
    public boolean backlitLift;
    /** Linear level that becomes pure white (1 = none). Lower = brighter, whiter background. */
    public float whitePoint = 1f;
    public ColorLut lut;

    private Look(String name) { this.name = name; }

    /** Neutral iPhone-like rendering with HDR (v0.2 behaviour). */
    public static Look natural() {
        Look l = new Look("iPhone naturale");
        l.localContrast = 0.72f;
        l.highKey = false;
        l.backlitLift = true;
        l.lut = ColorLut.appleLike(33, 0.6f, 1f);
        return l;
    }

    /** Bright, high-key selfie with rosy skin: measured on the reference "capelli bagnati" photo. */
    public static Look luminoso() {
        Look l = new Look("Luminoso");
        l.localContrast = 1.0f;
        l.highKey = true;
        l.faceTarget = 97f;
        l.skinRG = 1.555f;
        l.skinBG = 0.877f;
        l.skinStrength = 1f;
        l.whitePoint = 0.7f;
        l.lut = ColorLut.look(33, 0.4f, 1.06f, 1.06f, 1.0f, 1f);
        return l;
    }

    /** Warm evening interior, ambient light kept warm, no clipped highlights: measured on the "duo" photo. */
    public static Look sera() {
        Look l = new Look("Sera calda");
        l.localContrast = 0.95f;
        l.highKey = false;
        l.faceTarget = 86f;
        l.skinRG = 1.46f;
        l.skinBG = 0.893f;
        l.skinStrength = 1f;
        l.lut = ColorLut.look(33, 1.5f, 1.10f, 1.08f, 1.0f);
        return l;
    }
}
