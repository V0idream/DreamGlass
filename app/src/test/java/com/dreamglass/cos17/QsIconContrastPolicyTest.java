package com.dreamglass.cos17;

import com.coloros16.liquidglass.hook.BackgroundContrastMap;
import com.coloros16.liquidglass.hook.LocalTextContrast;
import com.coloros16.liquidglass.hook.QsIconContrastPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class QsIconContrastPolicyTest {
    private final QsIconContrastPolicy p=QsIconContrastPolicy.INSTANCE;
    @Test public void neutralForegroundsIncludeMutedAndTranslucentIcons() {
        for(int c:new int[]{0xffffffff,0xff000000,0xff888888,0x80ffffff,0xffeeecec}) assertTrue(p.neutral(c));
        assertFalse(p.neutral(0x00ffffff));
    }
    @Test public void originalAlphaAndSharedTextDecisionArePreserved() {
        for(int alpha:new int[]{1,32,128,255}) for(double light:new double[]{0,.05,.2,.5,1}) {
            int original=(alpha<<24)|0xffffff;
            assertEquals(Integer.valueOf(LocalTextContrast.INSTANCE.readableColor(original,light)),p.foreground(original,light));
            assertEquals(alpha,p.foreground(original,light)>>>24);
        }
    }
    @Test public void coloredStatusAndUnknownBackgroundAreNotReplaced() {
        for(int c:new int[]{0xff0066ff,0xffffc000,0xff00aa33,0xffdd1122}) {
            assertFalse(p.neutral(c));assertNull(p.foreground(c,.5));
        }
        assertNull(p.foreground(0xffffffff,null));
        assertNull(p.foreground(0xffffffff,Double.NaN));
    }
    @Test public void adjacentButtonsChooseTheirOwnBlackAndWhite() {
        BackgroundContrastMap map=BackgroundContrastMap.fromPixels(new int[]{0xffffffff,0xff000000},2,1);
        assertEquals(Integer.valueOf(0xff000000),p.foreground(0xffffffff,map.mean(0,0,.5f,1)));
        assertEquals(Integer.valueOf(0xffffffff),p.foreground(0xffffffff,map.mean(.5f,0,1,1)));
    }
    @Test public void ownershipAcceptsQsIconsButNotImageBackdropsOrAvatars() {
        assertTrue(p.qsHost("com.android.systemui.qs.FooterActionsView"));
        assertTrue(p.qsHost("com.oplus.systemui.plugins.qs.TileView"));
        assertTrue(p.qsHost("OplusQSResizeableTileViewOneXOne"));
        assertFalse(p.qsHost("com.android.launcher.IconView"));
        for(String id:new String[]{"icon","qs_icon","settings_button","btn_edit"}) assertTrue(p.iconId(id));
        for(String id:new String[]{"icon_background","icon_bg","user_avatar","media_artwork","preview",""}) assertFalse(p.iconId(id));
    }
    @Test public void mediaVolumeNotificationsAndStatusBarRemainOutsideScope() {
        for(String name:new String[]{"OplusQSMediaPlayer","OplusQSSeekBar","VolumeDialogImpl",
            "NotificationRow","NotificationBackgroundView","StatusBarIconView","BatteryMeterView"}) assertTrue(p.excluded(name));
        assertFalse(p.excluded("NotificationShadeWindowView"));
        assertFalse(p.excluded("OplusQSIconViewImpl"));
    }
}
