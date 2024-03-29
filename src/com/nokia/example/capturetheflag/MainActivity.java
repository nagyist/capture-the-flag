/**
 * Copyright (c) 2014 Microsoft Mobile.
 * See the license text file delivered with this project for more information.
 */

package com.nokia.example.capturetheflag;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import com.here.android.mapping.MapAnimation;
import com.nokia.example.capturetheflag.iap.PremiumHandler;
import com.nokia.example.capturetheflag.location.GameMap;
import com.nokia.example.capturetheflag.network.NetworkClient;
import com.nokia.example.capturetheflag.network.model.Game;
import com.nokia.push.PushRegistrar;

/**
 * Main Activity of the application. This Activity is responsible for
 * initialisation and de-initialisation of push notifications. Also when IAP
 * request is made the results are received on the onActivityResult() method.
 * 
 * Note that this class overrides the default back button behavior because while
 * the game is running and user presses back, we first show a small dialog
 * (resume/drop out) and it requires diverting from the normal backstack
 * handling. For the back button to work normally, we implement BackCallBack
 * interface on classes that need to know when back is pressed. Check usage from
 * CreateGameFragment or JoinGameFragment.
 */
public class MainActivity extends Activity implements
        GameEndedDialogFragment.DialogButtonListener {
    private static final String TAG = "CtF/MainActivity";
    private static final String INAPP_PURCHASE_DATA_KEY = "INAPP_PURCHASE_DATA";
    private static final String PRODUCT_ID_KEY = "productId";

    private GameMap mGameMap = null;
    private Controller mController = null;
    private MenuItem mBuyPremiumMenuItem = null;
    private BackCallback mBackKeyCallback = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FragmentManager fragmanager = getFragmentManager();
        mController = (Controller) fragmanager
                .findFragmentByTag(Controller.FRAGMENT_TAG);

        // First time or fragment couldn't be retained
        if (mController == null) {
            mController = new Controller();
            fragmanager.beginTransaction()
                    .add(mController, Controller.FRAGMENT_TAG).commit();
        }

        mGameMap = (GameMap) fragmanager.findFragmentById(R.id.mapfragment);
        setupPushNotification();

        if (savedInstanceState == null) {
            showGameMenu(null);
        }
    }

    @Override
    protected void onResume() {
        // Engine.getInstance().connectionNotIdle();
        super.onResume();
    }

    @Override
    protected void onStop() {
        // Engine.getInstance().connectionIdle();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PushRegistrar.onDestroy(this);
        mController.cleanUp();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        mBuyPremiumMenuItem = menu.findItem(R.id.buy_premium_menuitem);
        mBuyPremiumMenuItem.setVisible(!mController.isPremium());
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean retval = false;
        
        switch (item.getItemId()) {
            case R.id.buy_premium_menuitem:
                FragmentTransaction transaction = getFragmentManager().beginTransaction();
                PurchasePremiumFragment premiumFragment = new PurchasePremiumFragment();
                transaction.addToBackStack(null);
                transaction.add(R.id.fragmentcontainer, premiumFragment,
                        PurchasePremiumFragment.FRAGMENT_TAG);
                transaction.commit();
                retval = true;
                break;
            case R.id.help_menuitem:
                Intent intent = new Intent(this, HelpActivity.class);
                startActivity(intent);
                retval = true;
                break;
            case R.id.about_menuitem:
                Intent i = new Intent(this, AboutActivity.class);
                startActivity(i);
                retval = true;
                break;
            case R.id.server_settings_menuitem:
                final MainActivity context = this;
                
                ServerSettingsDialog dialog = new ServerSettingsDialog(this) {
                    @Override
                    public boolean onOkClicked(final String url, final String port) {
                        Log.i(TAG, "Server URL set to " + url + " and port as " + port + ".");
                        final int portAsInt = Integer.parseInt(port);
                        Settings.setServerUrl(url, context);
                        Settings.setServerPort(portAsInt, context);
                        
                        // Try to (re)connect
                        NetworkClient client = mController.getNetworkClient();
                        
                        if (client.isConnected()) {
                            client.disconnect();
                        }
                        
                        client.connect(url, portAsInt);
                        return true;
                    }
                };
                
                dialog.show();
                retval = true;
                break;
            default:
                retval = super.onOptionsItemSelected(item);
                break;
        }
        
        return retval;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (data.getIntExtra("RESPONSE_CODE", -100) == 0) {
                String purchaseData = data
                        .getStringExtra(INAPP_PURCHASE_DATA_KEY);
                
                try {
                    JSONObject json = new JSONObject(purchaseData);
                    String productId = json.getString(PRODUCT_ID_KEY);
                    
                    if (productId.equals(PremiumHandler.PREMIUM_PRODUCT_ID)) {
                        /*
                         * Hide the premium menu item since the premium version
                         * has already been purchased.
                         */
                        unlockPremium();
                        Settings.setPremium(productId, this);
                        Log.d(TAG, "Premium version already purchased.");
                        getFragmentManager().popBackStack();
                    }
                }
                catch (JSONException e) {
                    Log.e(TAG, "Error in purchase result handling: " + e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mBackKeyCallback != null) {
            mBackKeyCallback.onBackPressed();
        } else {
            /*
             * If no back callback, we check if the game is running and if it
             * is, we show the pause dialog.
             */
            if (mController.getCurrentGame() != null
                    && !mController.getCurrentGame().getHasEnded()) {
                PauseDialog dialog = new PauseDialog();
                dialog.show(getFragmentManager(), PauseDialog.FRAGMENT_TAG);
            } else {
                super.onBackPressed();
            }
        }
    }

    @Override
    public void OkClicked() {
        showGameMenu(null);
        mGameMap.clearMarkers();
    }

    /**
     * Called when a game is started. Sets the markers (flags, other players),
     * centers the map to user position and adjusts the zoom level.
     * 
     * @param game
     *            The instance of the game that was started.
     */
    public void startGame(Game game) {
        Log.d(TAG, "startGame()");
        mGameMap.setMarkers(game, mController.getPlayer());
        mGameMap.centerMapToUserPosition(
                GameMap.DEFAULT_MAP_ZOOM_LEVEL_IN_GAME, MapAnimation.LINEAR);
    }

    public void unlockPremium() {
        if (mBuyPremiumMenuItem != null) {
            mBuyPremiumMenuItem.setVisible(false);
        }
    }

    public void setBackCallback(BackCallback callback) {
        mBackKeyCallback = callback;
    }

    public void removeBackCallback() {
        mBackKeyCallback = null;
    }

    /**
     * Shows the game menu.
     * 
     * @param removable
     *            The fragment to be removed from the activity when menu is
     *            shown.
     */
    protected void showGameMenu(Fragment removable) {
        FragmentTransaction transaction = getFragmentManager()
                .beginTransaction();

        if (removable != null) {
            transaction.remove(removable);
        }

        // Create and show the dialog
        Fragment newFragment = new GameMenuFragment();
        transaction.add(R.id.fragmentcontainer, newFragment,
                GameMenuFragment.FRAGMENT_TAG);
        transaction.commit();
    }

    private void setupPushNotification() {
        Log.i(TAG, "Setting up Nokia Notifications...");
        PushRegistrar.checkDevice(this);
        PushRegistrar.checkManifest(this);
        final String regId = PushRegistrar.getRegistrationId(this);
        
        if (regId == null || regId.isEmpty()) {
            Log.i(TAG, "No registration ID stored.");
            PushRegistrar.register(this, PushIntentService.SENDER_ID);
        }
    }

    /**
     * Interface for classes that need to know when back is pressed. Only to be
     * used by views and the views should remove themselves via
     * removeBackCallback(), when they aren't on the top of the view stack
     * anymore. Best to be used with fragments.
     */
    public interface BackCallback {
        public void onBackPressed();
    }
}
