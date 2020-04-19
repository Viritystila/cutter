layout(location = 0) in vec4 vertexPosition_modelspace;
layout(location = 1) in vec3 colors_modelspace;
layout(location = 2) in vec3 index_modelspace;
layout(location = 3) in vec2 uv_modelspace;
layout(location = 4) in vec3 normals_modelspace;

mat4 rotationMatrix(vec3 axis, float angle) {
    axis = normalize(axis);
    float s = sin(angle);
    float c = cos(angle);
    float oc = 1.0 - c;

    return mat4(oc * axis.x * axis.x + c,           oc * axis.x * axis.y - axis.z * s,  oc * axis.z * axis.x + axis.y * s,  0.0,
                oc * axis.x * axis.y + axis.z * s,  oc * axis.y * axis.y + c,           oc * axis.y * axis.z - axis.x * s,  0.0,
                oc * axis.z * axis.x - axis.y * s,  oc * axis.y * axis.z + axis.x * s,  oc * axis.z * axis.z + c,           0.0,
                0.0,                                0.0,                                0.0,                                1.0);
}

#define PI 3.14159
out vec2 texCoordV;
out vec3 Normal;
void main(void) {
  texCoordV=uv_modelspace;
Normal=normals_modelspace;
  float time = iGlobalTime + 20.0;
  float v = gl_VertexID;
float vertex = mod(v, 8.);
//v = (v-vertex)/6.;
float a1 = 2.0 ;//mod(1, 32.);
//v = (v-a1)/32.;
  float a2 = 1.0;

  float a1n = (a1+.5)/32.*2.*PI;
  float a2n = (a2+.5)/32.*2.*PI;

a1 += 1.0 ;//mod(vertex,1.);
a2 += 2.0 ;// vertex==2.||vertex>=4.?1.:0.;

  a1 = a1/32.*2.*PI;
  a2 = a2/32.*2.*PI;
  float snd = 0.0;
  vec3 pos = vec3(cos(a1)*cos(a2),sin(a2),sin(a1)*cos(a2));
  vec3 norm = vec3(cos(a1n)*cos(a2n),sin(a2n),sin(a1n)*cos(a2n));
  norm = vec3(cos(a1)*cos(a2),sin(a2),sin(a1)*cos(a2));

pos.xz *= mat2(cos(time),sin(time),-sin(time),cos(time));
pos.yz *= mat2(cos(time),sin(time),-sin(time),-cos(time));
  norm.xz *= mat2(cos(time),sin(time),-sin(time),cos(time));
  norm.yz *= mat2(cos(time),sin(time),-sin(time),cos(time));
  vec4 sp=vec4(pos.x,pos.y, pos.z, 1);
mat4 rotma=rotationMatrix(vec3(0.0+time,time, 1.0), time);

//texCoordV=vec2(cos(a1)*cos(a2),sin(a2));


  gl_Position = vertexPosition_modelspace*vec4(1, 1,1 ,2)*rotma;
}
