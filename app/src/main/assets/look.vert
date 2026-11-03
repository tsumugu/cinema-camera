attribute vec2 aPosition;
varying vec2 vUv;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    // Bitmaps are uploaded top row first. Screen top must sample texture y=0.
    vUv = vec2(aPosition.x * 0.5 + 0.5, 0.5 - aPosition.y * 0.5);
}
