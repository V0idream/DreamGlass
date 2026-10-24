import com.coloros16.liquidglass.hook.ButtonBackdropBlur;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Offline fixture using the actual compiled Kotlin filter; no Android or SystemUI claim. */
class render_button_backdrop_fixture {
    public static void main(String[] args) throws Exception {
        int w=360, h=180;
        BufferedImage background=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=background.createGraphics();
        g.setPaint(new GradientPaint(0,0,new Color(20,100,160),w,h,new Color(150,40,100)));
        g.fillRect(0,0,w,h);
        g.setColor(new Color(200,230,240));
        for(int x=0;x<w;x+=24) g.drawLine(x,0,x,h);
        g.setFont(new Font("SansSerif",Font.BOLD,20));
        for(int y=30;y<h;y+=45) g.drawString("DreamGlass 012345",15,y);
        g.dispose();
        int[] raw=background.getRGB(0,0,w,h,null,0,w);
        BufferedImage sheet=new BufferedImage(w*3,h+32,BufferedImage.TYPE_INT_ARGB);
        Graphics2D s=sheet.createGraphics();s.setColor(new Color(12,17,32));s.fillRect(0,0,w*3,h+32);
        for(int i=0;i<3;i++) {
            float sigma=new float[]{0,4,16}[i];
            long start=System.nanoTime();
            int[] result=ButtonBackdropBlur.INSTANCE.apply(raw,w,h,sigma);
            System.out.printf("sigma=%s, timeMs=%.2f%n",sigma,(System.nanoTime()-start)/1e6);
            BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0,0,w,h,result,0,w);
            s.drawImage(image,i*w,32,null);s.setColor(Color.WHITE);
            s.drawString("OFFLINE CPU FIXTURE - sigma "+sigma,i*w+10,20);
        }
        s.dispose();ImageIO.write(sheet,"png",new File(args[0]));
    }
}
