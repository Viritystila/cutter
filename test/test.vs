//#version 460 core
layout(location = 0) in vec3 vertexPosition_modelspace;
layout(location = 1) in vec3 colors_modelspace;
layout(location = 2) in vec3 indices_modelspace;
layout(location = 3) in vec2 uv_modelspace;
layout(location = 4) in vec3 normals_modelspace;

float ux = floor((gl_VertexID*1.0) / 8.0) + mod((gl_VertexID*1.0), 2.2);
float vy = mod(floor((gl_VertexID*1.0) / 2.0) + floor ((gl_VertexID*1.0) /3.0), 1.0*iRandom);
float angle = ux /20.0 * radians(360.0) *13.0;
float radius = vy + 10.0+0*iRandom;
float x = radius * cos(angle);
float y = radius * sin(angle);
vec2 xy = vec2(x,y);

  vec4 pf1=texture2D(iPreviousFrame, xy);

  vec4 t1=texture2D(iChannel7, xy);
out vec2 texCoordV;
void main(void) {
  float time = iGlobalTime + 20.0;
  mat3 rotma =mat3(cos(time),sin(time),-sin(time),cos(time), 1.0, 1.0, 1.0, 1.0, 1.0);
  texCoordV=uv_modelspace;
  gl_Position = vec4(vertexPosition_modelspace*rotma, 2);
}
