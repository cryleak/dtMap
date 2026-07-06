#version 330

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
