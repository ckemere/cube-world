package com.ckemere.cubeworld.geometry;

/**
 * Resolution of a point in a seam margin (the void strip just beyond a
 * stitched edge): {@code source} is the real position whose content the
 * margin point displays, and {@code toSource} is the transform that carried
 * the margin point there (its quarter turns tell you how to rotate block
 * states; its inverse maps real positions back to margin images).
 */
public record MarginSource(EdgeLink link, EdgeTransform toSource, Vec2 source) {
}
