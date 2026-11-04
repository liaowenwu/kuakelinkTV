package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Lightweight record artwork, with a fixed tonearm and rotating album label. */
public final class VinylView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap artwork;
    private float angle;
    private boolean playing;
    private ValueAnimator spin;
    public VinylView(Context context, AttributeSet attrs) { super(context, attrs); }
    void setArtwork(Bitmap bitmap) { artwork = bitmap; invalidate(); }
    void setPlaying(boolean value) { playing = value; updateSpin(); }
    private void updateSpin() {
        if (playing && isShown() && ValueAnimator.areAnimatorsEnabled()) {
            if (spin == null) {
                spin = ValueAnimator.ofFloat(angle, angle + 360f);
                spin.setDuration(26000); spin.setRepeatCount(ValueAnimator.INFINITE);
                spin.setInterpolator(new LinearInterpolator());
                spin.addUpdateListener(a -> { angle = (float) a.getAnimatedValue(); invalidate(); });
                spin.start();
            }
        } else if (spin != null) { spin.cancel(); spin = null; }
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); updateSpin(); }
    @Override protected void onDetachedFromWindow() { if (spin != null) { spin.cancel(); spin = null; } super.onDetachedFromWindow(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float r = Math.min(getWidth(), getHeight()) * .43f, x = getWidth() * .48f, y = getHeight() * .51f;
        paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(0x35051018);
        canvas.drawCircle(x, y + 10, r + 5, paint);
        paint.setColor(Color.WHITE);
        paint.setShader(new SweepGradient(x, y, new int[]{0xFF101820,0xFF2C3942,0xFF111820,0xFF40505B,0xFF111820}, null));
        canvas.drawCircle(x, y, r, paint); paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1, r / 220));
        for (float groove = .48f; groove < .98f; groove += .018f) {
            paint.setColor(0x286F8491); canvas.drawCircle(x, y, r * groove, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.save(); canvas.rotate(angle, x, y);
        Path clip = new Path(); clip.addCircle(x, y, r * .43f, Path.Direction.CW); canvas.clipPath(clip);
        paint.setColor(Color.WHITE);
        if (artwork != null) canvas.drawBitmap(artwork, null, new RectF(x-r*.43f,y-r*.43f,x+r*.43f,y+r*.43f),paint);
        else {
            paint.setShader(new LinearGradient(x-r*.4f,y-r*.4f,x+r*.4f,y+r*.4f,0xFF91CED2,0xFF507BA3,Shader.TileMode.CLAMP));
            canvas.drawCircle(x,y,r*.43f,paint); paint.setShader(null);
            paint.setColor(0xFFDAE5DC); paint.setTextSize(r*.24f); paint.setTextAlign(Paint.Align.CENTER); canvas.drawText("♪",x,y+r*.09f,paint);
        }
        canvas.restore(); paint.setColor(0xFF1C2932); canvas.drawCircle(x,y,r*.035f,paint);
        paint.setColor(0xFFD6E2E3); paint.setStrokeWidth(r*.025f); paint.setStrokeCap(Paint.Cap.ROUND);
        float armX=x+r*.92f, armY=y-r*.9f;
        canvas.drawCircle(armX,armY,r*.055f,paint);
        canvas.drawLine(armX,armY,armX-r*.12f,armY+r*.56f,paint);
        canvas.drawLine(armX-r*.12f,armY+r*.56f,armX-r*.31f,armY+r*.77f,paint);
        paint.setStrokeWidth(r*.065f); canvas.drawLine(armX-r*.31f,armY+r*.77f,armX-r*.37f,armY+r*.84f,paint);
    }
}
