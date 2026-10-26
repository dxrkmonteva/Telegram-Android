package org.telegram.ui.Components.chat.buttons;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.DrawableRes;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CounterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;


@SuppressLint("ViewConstructor")
public class ChatActivityBlurredRoundPageDownButton extends FrameLayout {
    private final Theme.ResourcesProvider resourcesProvider;

    private ChatActivityBlurredRoundButton buttonView;
    private CounterView counterView;

    public ChatActivityBlurredRoundPageDownButton(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
        setClipToPadding(false);
    }

    private TideView tideView;
    private final int touchSlop;
    private float downX, downY;
    private boolean pulling;

    /** Turns the circle into the "Tide" glass: the unread counter becomes water, drag up to pour it out. */
    public void enableTide() {
        if (tideView != null) {
            return;
        }
        tideView = new TideView(getContext(), resourcesProvider);
        // below the counter, above the glass button; wider/taller than the button, draws outside of it
        addView(tideView, 1, LayoutHelper.createFrame(TideView.VIEW_W_DP, TideView.VIEW_H_DP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        tideView.setClickable(false);
        tideView.setEntryEnabled(org.telegram.messenger.SharedConfig.tideFillAnimation);
        tideView.setEntryListener((landed, total) -> {
            if (counterView != null) {
                counterView.setCount(landed, true);
            }
        });
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (tideView != null && isEnabled()) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    pulling = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = downY - ev.getY();
                    float dx = Math.abs(ev.getX() - downX);
                    if (!pulling && dy > touchSlop * 1.6f && dy > dx * 1.2f) {
                        pulling = true;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        tideView.setPull(dy);
                        return true;
                    }
                    break;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (tideView != null && pulling) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    tideView.setPull(downY - ev.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pulling = false;
                    boolean poured = tideView.release(ev.getActionMasked() == MotionEvent.ACTION_CANCEL);
                    if (poured) {
                        callOnClick();
                    }
                    return true;
            }
            return true;
        }
        return super.onTouchEvent(ev);
    }

    public void addButtonView(ChatActivityBlurredRoundButton button, int size) {
        this.buttonView = button;
        addView(button, LayoutHelper.createFrame(size, size, Gravity.BOTTOM));
        button.setIconPadding(dp(2));
    }


    private boolean reversedCounter;

    public void reverseCounter() {
        reversedCounter = true;
        if (counterView != null) {
            counterView.setReverse(true);
        }
    }

    public void setCount(int count, boolean animated) {
        if (counterView == null) {
            counterView = new CounterView(getContext(), resourcesProvider);
            counterView.setReverse(reversedCounter);
            addView(counterView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 28, Gravity.TOP));
        }

        counterView.setCount(count, animated);
        if (tideView != null) {
            tideView.setCount(count, animated);
            if (tideView.isEntryPending()) {
                counterView.setCount(0, false);
            }
        }
    }





    public void showLoading(boolean loading, boolean animated) {
        buttonView.showLoading(loading, animated);
    }

    @Override
    public void setEnabled(boolean enabled) {
        setEnabled(enabled, false);
    }

    public void setEnabled(boolean enabled, boolean animated) {
        super.setEnabled(enabled);
        buttonView.setEnabled(enabled, animated);
    }

    public void reverseIconByY() {
        buttonView.reverseIconByY();
    }

    public void updateColors() {
        if (buttonView != null) {
            buttonView.updateColors();
            if (tideView != null) {
                tideView.updateColors();
            }
            invalidate();
        }
    }

    public static ChatActivityBlurredRoundPageDownButton create(
        Context context,
        int size, int iconSize,
        Theme.ResourcesProvider resourcesProvider,
        BlurredBackgroundDrawableViewFactory factory,
        BlurredBackgroundColorProvider colorProvider,
        @DrawableRes int res
    ) {
        ChatActivityBlurredRoundPageDownButton button = new ChatActivityBlurredRoundPageDownButton(context, resourcesProvider);
        button.addButtonView(ChatActivityBlurredRoundButton.create(context, factory, colorProvider, resourcesProvider, res, iconSize), size);
        ScaleStateListAnimator.apply(button, .13f, 2f);

        return button;
    }
}
