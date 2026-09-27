package jp.bunkaich.sukashimotion;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import java.io.*;

/** Optional photo behind Folduo home. Only a downscaled private copy is kept; nothing leaves the device. */
final class HomeBackground {
    private static final int MAX_SIDE=2400;
    private HomeBackground(){}
    static File file(Context c){return new File(c.getFilesDir(),"home_background.jpg");}
    static long stamp(Context c){File f=file(c);return f.isFile()?f.lastModified():0;}
    static void save(Context c,Uri uri)throws IOException{
        // A software bitmap keeps HomeScene.captureForBlur able to draw it on a plain Canvas.
        Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(),uri),(decoder,info,source)->{
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1f,MAX_SIDE/(float)Math.max(w,h));
            if(scale<1)decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
        });
        File temp=new File(c.getFilesDir(),"home_background.tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,92,out))throw new IOException("JPEG encoding failed");}
        finally{bitmap.recycle();}
        if(!temp.renameTo(file(c))){temp.delete();throw new IOException("Could not store the image");}
    }
    static void clear(Context c){file(c).delete();}
    static Bitmap load(Context c){File f=file(c);return f.isFile()?BitmapFactory.decodeFile(f.getPath()):null;}
}
