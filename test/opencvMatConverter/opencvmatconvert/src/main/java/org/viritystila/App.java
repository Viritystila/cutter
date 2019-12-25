package org.viritystila;


import java.nio.Buffer;
import java.nio.ByteBuffer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.BytePointer;
import org.opencv.core.Mat;
public class App 
{
    public static void main( String[] args )
    {
        System.out.println( "Hello World!" );
    }
    
    public java.nio.ByteBuffer convert(final org.opencv.core.Mat mat) {
        if (mat == null) {
            return null;
        } else {
            ByteBuffer byteBuffer = new BytePointer() { { address = mat.dataAddr(); } }.capacity(mat.rows() * mat.step1() * mat.elemSize1()).asByteBuffer();
            return byteBuffer;
        }
    }
}
