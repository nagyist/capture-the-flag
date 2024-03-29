/**
 * Copyright (c) 2014 Microsoft Mobile.
 * See the license text file delivered with this project for more information.
 */

package com.nokia.example.capturetheflag;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.here.android.common.GeoCoordinate;
import com.here.android.common.GeoPosition;
import com.here.android.common.LocationMethod;
import com.here.android.common.LocationStatus;
import com.here.android.common.PositionListener;
import com.here.android.mapping.MapFactory;
import com.here.android.search.geocoder.ReverseGeocodeRequest;
import com.nokia.example.capturetheflag.iap.PremiumHandler;
import com.nokia.example.capturetheflag.iap.PremiumHandler.PremiumHandlerListener;
import com.nokia.example.capturetheflag.location.GameMap;
import com.nokia.example.capturetheflag.network.FlagCapturedResponse;
import com.nokia.example.capturetheflag.network.GameListResponse;
import com.nokia.example.capturetheflag.network.JSONResponse;
import com.nokia.example.capturetheflag.network.JoinedResponse;
import com.nokia.example.capturetheflag.network.OfflineClient;
import com.nokia.example.capturetheflag.network.SocketIONetworkClient;
import com.nokia.example.capturetheflag.network.UpdatePlayerRequest;
import com.nokia.example.capturetheflag.network.UpdatePlayerResponse;
import com.nokia.example.capturetheflag.network.model.Game;
import com.nokia.example.capturetheflag.network.model.ModelConstants;
import com.nokia.example.capturetheflag.network.model.Player;
import com.nokia.example.capturetheflag.network.NetworkClient;

/**
 * Controller class is responsible for communicating server responses back to
 * the UI. It listens for message events from the server and from the IAP
 * server. It also maintains a state about the current Game and Player objects.
 * 
 * The class is implemented as a Fragment but it's a retained fragment, meaning
 * that it doesn't have an UI and it will be kept alive if possible i.e. if
 * there is enough memory for it.
 */
public class Controller
    extends Fragment
    implements PremiumHandlerListener, NetworkClient.NetworkListener, PositionListener
{
    public static final String FRAGMENT_TAG = "Controller";
    private static final String TAG = "CtF/Controller";

    private static Controller mSelf;
    private PremiumHandler mPremium = new PremiumHandler();
    private Game mCurrentGame;
    private Player mPlayer;
    private GameMap mMap;
    private NetworkClient mClient;
    private SocketIONetworkClient mSocketClient;
    private OfflineClient mOfflineClient;
    private Handler mUIHandler;
    private int mIsConnected = -1;
    private boolean mIsLocationFound = false;

    /**
     * Receiver to handle push notifications. When push note is received parse
     * it and show it to the user.
     */
    private BroadcastReceiver mPushHandler = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG + ".mPushHandler", "Received broadcast");
            
            try {
                Player capturer = new Player(
                        new JSONObject(intent
                                .getStringExtra(ModelConstants.CAPTURER_KEY))
                                .getJSONObject(ModelConstants.CAPTURED_BY_PLAYER_KEY));
                FlagCaptured(capturer);
            }
            catch (JSONException e) {
                // Received corrupted data, do nothing
                Log.e(TAG + ".mPushHandler", "Parse error: " + e.getMessage(), e);
            }
        }
    };

    public static Controller getInstance() {
        return mSelf;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSelf = this;
        mUIHandler = new Handler();
        setRetainInstance(true);
        mPremium.addListener(this);
        mSocketClient = new SocketIONetworkClient();
        mClient = mSocketClient;
        mClient.setListener(this);
        mClient.connect(
                Settings.getServerUrl(getActivity()),
                Settings.getServerPort(getActivity()));
        mOfflineClient = new OfflineClient();
        mOfflineClient.setListener(this);

    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mMap = (GameMap) getFragmentManager()
                .findFragmentById(R.id.mapfragment);
        mMap.setPositionListener(this);
        mPremium.connect(activity);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mMap = null; // Remove the reference since map is not retained, would
                     // leak everything
        Log.d(TAG, "onDetach(): getActivity() returns "
                + (getActivity() == null ? "null" : "not null"));
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "Unregistering broadcast receiver");
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(
                mPushHandler);
        mClient.setConnectionIdle(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "Registering broadcast receiver");
        
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(
                mPushHandler,
                new IntentFilter(PushIntentService.PUSH_MESSAGE_ACTION));
        mClient.setConnectionIdle(false);
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mSocketClient.cleanUp();
        mSelf = null;
    }

    @Override
    public void onGameListMessage(GameListResponse rsp) {
        FragmentManager manager = getFragmentManager();
        
        if (manager != null) {
            GameMenuFragment menu = (GameMenuFragment) getFragmentManager()
                    .findFragmentByTag(GameMenuFragment.FRAGMENT_TAG);
            
            if (menu != null && menu.isVisible()) {
                Log.d(TAG, "Menu is not null");
                menu.setGames(rsp.getGames());
            }
        }
    }

    @Override
    public void onJoinedMessage(JoinedResponse joined) {
        Fragment CreateGameFrg = getFragmentManager().findFragmentByTag(
                CreateGameFragment.FRAGMENT_TAG);
        
        if (CreateGameFrg != null) {
            getFragmentManager().beginTransaction().remove(CreateGameFrg)
                    .commit();
        }
        
        setCurrentGame(joined.getJoinedGame());
        mPlayer = joined.getPlayer();
        ((MainActivity) getActivity()).startGame(joined.getJoinedGame());
    }

    @Override
    public void onUpdatePlayerMessage(UpdatePlayerResponse update) {
        if (mCurrentGame == null) {
            return;
        }
        
        Log.d(TAG, "onUpdatePlayerMessage()");
        Player updated = update.getUpdatedPlayer();
        
        if (!mCurrentGame.getPlayers().contains(update.getUpdatedPlayer())) {
            mCurrentGame.getPlayers().add(updated);
        }
        else {
            int i = mCurrentGame.getPlayers().indexOf(updated);
            Player old = mCurrentGame.getPlayers().get(i);
            updated.setMarker(old.getMarker()); // Copy the marker from old to new
            mCurrentGame.getPlayers().set(i, updated);
        }
        
        mMap.updatePlayerMarker(updated);
    }

    @Override
    public void onError(JSONResponse error) {
        Log.w(TAG, "onError()");
        
        AlertDialog.Builder errordialog = new AlertDialog.Builder(getActivity());
        errordialog.setTitle("Error");
        
        if (error.getErrorCode() == JSONResponse.ERROR_GAME_FULL) {
            errordialog.setMessage(getString(R.string.game_full));
        }
        else if (error.getErrorCode() == JSONResponse.ERROR_EXCEPTION) {
            errordialog.setMessage(error.getException().getMessage());
        }
        
        errordialog.setPositiveButton(getString(android.R.string.ok),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        MainActivity m = (MainActivity) getActivity();
                        m.showGameMenu(null);
                    }
                });
        
        errordialog.create().show();
    }

    @Override
    public void IapInitialized(int resultcode) {
        Log.d(TAG, "IAP is initialized");
        mPremium.checkIfPremiumPurchased();
    }

    @Override
    public void setPremiumPurchased(boolean isPurchased) {
        Log.d(TAG, "Premium purchased: " + isPurchased);
        
        /*
         * TODO: - Hide the loading indicator/splash screen - If premium, notify
         * activity
         */
        
        if (isPurchased) {
            ((MainActivity) getActivity()).unlockPremium();
        }
    }

    @Override
    public void onPriceReceived(String premiumPrice) {
        // Not implemented
    }

    /**
     * In case you don't want to use the push notifications to inform users that
     * the game has ended, you can enable the ending info to be sent via TCP
     * socket and handle the message here.
     */
    @Override
    public void onFlagCapturedMessage(FlagCapturedResponse captured) {
        Player p = captured.getCapturer();
        FlagCaptured(p);
    }

    @Override
    public void onPositionFixChanged(LocationMethod arg0, LocationStatus arg1) {
        // Not implemented
    }

    @Override
    public void onPositionUpdated(LocationMethod arg0, GeoPosition position) {
        mIsLocationFound = true;
        //Log.d(TAG, "Position updated");
        Player user = getPlayer();
        
        // Only if game is running, we send updated location to the server
        if (user != null && getCurrentGame() != null
                && !getCurrentGame().getHasEnded()) {
            Log.d(TAG, "updating user position");
            GeoCoordinate coordinate = position.getCoordinate();
            
            if (user.getLatitude() != coordinate.getLatitude()
                    || user.getLongitude() != coordinate.getLongitude()) {
                user.setLatitude(coordinate.getLatitude());
                user.setLongitude(coordinate.getLongitude());
                UpdatePlayerRequest upr = new UpdatePlayerRequest(user,
                        getCurrentGame().getId());
                mClient.emit(upr);
                
                // Update the marker
                mMap.updatePlayerMarker(user);
            }
        }
        
        /*
         * If the menu is visible, we create and send the reverse geocode
         * request so that we can update the user location in the fragment.
         */
        GameMenuFragment menu = (GameMenuFragment) getFragmentManager()
                .findFragmentByTag(GameMenuFragment.FRAGMENT_TAG);
        
        if (menu != null) {
            ReverseGeocodeRequest request = MapFactory.getGeocoder()
                    .createReverseGeocodeRequest(position.getCoordinate());
            request.execute(menu);
        }
    }

    /**
     * Displays the connection status using a toast message.
     */
    @Override
    public void onNetworkStateChange(final boolean isConnected, NetworkClient client) {
        mUIHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean showToast = false;
                
                if (mIsConnected == -1
                        || (isConnected && mIsConnected == 0)
                        || (!isConnected && mIsConnected == 1))
                {
                    showToast = true;
                }
                
                Activity activity = getActivity();
                
                if (showToast && activity != null) {
                    final int toastTextId = isConnected
                            ? R.string.connected_to_server
                            : R.string.not_connected_to_server;
                    
                    Toast toast = Toast.makeText(activity,
                            getString(toastTextId), Toast.LENGTH_SHORT);
                    toast.show();
                    mIsConnected = isConnected ? 1 : 0;
                }
            }
        });
    }

    /**
     * Checks from saved preferences if the app is the premium version.
     * 
     * @return True if premium, false otherwise.
     */
    public boolean isPremium() {
        return Settings.getPremium(getActivity()).length() > 0;
    }

    public void setCurrentGame(Game g) {
        if (g != null) {
            Log.d(TAG, "Setting as current game: " + g.getId());
        }
        
        mCurrentGame = g;
    }

    public Game getCurrentGame() {
        return mCurrentGame;
    }

    public void clearGame() {
        mCurrentGame = null;
        mMap.clearMarkers();
    }

    public Player getPlayer() {
        return mPlayer;
    }

    public PremiumHandler getPremiumHandler() {
        return mPremium;
    }

    /**
     * Call this in activity's onDestroy() to release possible handles to the
     * activity object.
     */
    public void cleanUp() {
        mPremium.cleanup();
        mClient.disconnect();
    }

    public NetworkClient getNetworkClient() {
        return mClient;
    }

    /**
     * Switches to online/offline mode depending on the argument.
     * 
     * @param online If true, will switch to online mode. Otherwise will switch
     * to offline mode.
     */
    public void switchOnlineMode(boolean online) {
        if (online) {
            mClient = mSocketClient;
        }
        else {
            mClient = mOfflineClient;
            mOfflineClient.connect(null, 0);
        }
    }

    public boolean isLocationFound() {
        return mIsLocationFound;
    }

    private void FlagCaptured(Player capturer) {
        GameEndedDialogFragment dialog = new GameEndedDialogFragment();
        Bundle bundle = new Bundle();
        bundle.putString(GameEndedDialogFragment.PLAYER_NAME_KEY,
                capturer.getName());
        bundle.putString(GameEndedDialogFragment.TEAM_KEY, capturer.getTeam());
        dialog.setArguments(bundle);
        getFragmentManager().beginTransaction()
                .add(dialog, GameEndedDialogFragment.FRAGMENT_TAG).commit();
        mCurrentGame.setHasEnded(true);
    }
}
