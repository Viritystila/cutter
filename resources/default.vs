layout(location = 0) in vec4 vertexPosition_modelspace;
layout(location = 1) in vec3 colors_modelspace;
layout(location = 2) in vec3 index_modelspace;
layout(location = 3) in vec2 uv_modelspace;
layout(location = 4) in vec3 normals_modelspace;
layout(location = 5) in vec4 modelScale;
layout(location = 6) in vec4 modelPosition;
layout(location = 7) in mat4 modelRotation;



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
  vec4 _vertex=vertexPosition_modelspace;
  float rad=texture2D(iChannel1, uv_modelspace).r;

  //_vertex.x= _vertex.x+rad;
  //_vertex.y= _vertex.y+rad*rad;
  //_vertex.z= _vertex.z+rad*rad;


  texCoordV=uv_modelspace;
  vec4 iChannel1_texture=texture2D(iChannel1, texCoordV);
  Normal=normals_modelspace;
  float time = iGlobalTime + 20.0;
  float v = gl_VertexID;
  float vertex = mod(v, 8.);
  float a1 = 2.0 ;
  float a2 = 1.0;
  float a1n = (a1+.5)/32.*2.*PI;
  float a2n = (a2+.5)/32.*2.*PI;

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
  vec4 scales[3]=vec4[3](vec4(20,20,1,1),
                         vec4(1,1,1,0),
                         vec4(1,1,1,1));
  vec4 posits[3]=vec4[3](vec4(0,0,1,20),
                         vec4(0,1,-3,3),
                         vec4(-1,-1,-3,4));
  mat4 rotmas[3]=mat4[3](rotationMatrix(vec3(0.0,1, 1.0), 0),
                         rotationMatrix(vec3(2.0,1+time, 1.0+time), time),
                         rotationMatrix(vec3(1.0+1,1, 1.0), time));
  vec4 vertexPoss[3]=vec4[3](vertexPosition_modelspace,
                             //_vertex,

                             vertexPosition_modelspace+rad*vertexPosition_modelspace,

                             vertexPosition_modelspace
                             );
  vec4 scale=scales[iMeshID];
  vec4 posit=posits[iMeshID];
  mat4 rotma=rotmas[iMeshID];
  vec4 vertexPos=vertexPoss[iMeshID];
  gl_Position =vertexPos*scale*rotma+posit; // _vertex*vec4(1,1,1,2)*rotma;
}
