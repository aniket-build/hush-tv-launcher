package io.github.aniketrai.hush;

/** The "More Apps" screen: every app that isn't in Recently played, A–Z. Same UI as Home. */
public class MoreAppsActivity extends MainActivity {
    @Override
    protected boolean isMoreScreen() {
        return true;
    }
}
