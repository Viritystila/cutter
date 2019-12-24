//#version 460 core
layout(location = 0) in vec4 vertexPosition_modelspace;
layout(location = 1) in vec3 colors_modelspace;
void main(void) {
  gl_Position = vertexPosition_modelspace;   //* vec4(0.5*iRandom, 0.1, 0.0, 1.0);
}
