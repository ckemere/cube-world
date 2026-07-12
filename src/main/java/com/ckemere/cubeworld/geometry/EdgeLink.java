package com.ckemere.cubeworld.geometry;

/**
 * One stitched cube edge: the {@code sideA} boundary segment of {@code faceA}
 * (from {@code a0} to {@code a1}) is identified with the {@code sideB}
 * boundary segment of {@code faceB} (from {@code b0} to {@code b1}), with
 * endpoints listed in corresponding cube-vertex order. {@code aToB} carries
 * points just outside faceA across onto faceB; use {@code aToB().inverse()}
 * for the other direction.
 */
public record EdgeLink(CubeFace faceA, Side sideA, Vec2 a0, Vec2 a1,
                       CubeFace faceB, Side sideB, Vec2 b0, Vec2 b1,
                       EdgeTransform aToB) {
}
