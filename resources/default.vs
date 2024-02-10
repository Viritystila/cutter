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
//out vec2 texCoordV_vs;
//out vec3 Normal_vs;
out VertexData_vs {
    vec4 vertexPosition_modelspace;
    vec3 colors_modelspace;
    vec3 index_modelspace;
    vec2 uv_modelspace;
    vec3 normals_modelspace;
    vec4 modelScale;
    vec4 modelPosition;
    mat4 modelRotation;
} VertexOut;



void main(void) {
VertexOut.vertexPosition_modelspace=vertexPosition_modelspace;
VertexOut.colors_modelspace=colors_modelspace;
VertexOut.index_modelspace=index_modelspace;
VertexOut.uv_modelspace=uv_modelspace;
VertexOut.normals_modelspace=normals_modelspace;
VertexOut.modelScale=modelScale;
VertexOut.modelPosition=modelPosition;
VertexOut.modelRotation=modelRotation;

  vec4 _vertex=vertexPosition_modelspace;
  float rad=texture(iChannel2, uv_modelspace).b;
  rad=rad;//*rad*rad;


  //texCoordV_vs=uv_modelspace;
  //texCoordV_vs.y=1.0- texCoordV_vs.y;
  //VertexOut.uv_modelspace=texCoordV_vs;
  vec4 iChannel1_texture=texture(iChannel1, VertexOut.uv_modelspace);
  //Normal_vs=normals_modelspace;
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
  vec4 scales[4]=vec4[4](vec4(21,21,0,1),
                         vec4(1,1,1,2),
                         vec4(1,1,1,1),
                          vec4(1,1,1,1));
  vec4 posits[4]=vec4[4](vec4(0,0,1,20),
                         vec4(0,0,-2,0),
                         vec4(-1,-1,-1,4),
                         vec4(0,1,-3,3));
  mat4 rotmas[4]=mat4[4](rotationMatrix(vec3(0.0,1, 1.0), 0),
                         rotationMatrix(vec3(1.0+time,1+time, 1.0+time), time),
                         rotationMatrix(vec3(1.0+1,1, 1.0), time),
                         rotationMatrix(vec3(0.0,1, 1.0), 0));
  vec4 vertexPoss[4]=vec4[4](vertexPosition_modelspace,
                             //_vertex,

                             vertexPosition_modelspace+rad*vec4(normals_modelspace, 0),

                             vertexPosition_modelspace,
                              vertexPosition_modelspace
                             );
  vec4 scale=scales[iMeshID];
  vec4 posit=posits[iMeshID];
  mat4 rotma=rotmas[iMeshID];
  vec4 vertexPos=vertexPoss[iMeshID];
  gl_Position =vertexPos*scale*rotma+posit; // _vertex*vec4(1,1,1,2)*rotma;
}
