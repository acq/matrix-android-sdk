/*
 * Copyright 2015 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.matrixandroidsdk.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.support.v4.app.FragmentManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.MyUser;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.util.JsonUtils;
import org.matrix.matrixandroidsdk.ErrorListener;
import org.matrix.matrixandroidsdk.Matrix;
import org.matrix.matrixandroidsdk.MyPresenceManager;
import org.matrix.matrixandroidsdk.R;
import org.matrix.matrixandroidsdk.ViewedRoomTracker;
import org.matrix.matrixandroidsdk.db.ConsoleLatestChatMessageCache;
import org.matrix.matrixandroidsdk.db.ConsoleMediasCache;
import org.matrix.matrixandroidsdk.fragments.IconAndTextDialogFragment;
import org.matrix.matrixandroidsdk.fragments.MatrixMessageListFragment;
import org.matrix.matrixandroidsdk.fragments.MembersInvitationDialogFragment;
import org.matrix.matrixandroidsdk.fragments.RoomMembersDialogFragment;
import org.matrix.matrixandroidsdk.services.EventStreamService;
import org.matrix.matrixandroidsdk.util.NotificationUtils;
import org.matrix.matrixandroidsdk.util.RageShake;
import org.matrix.matrixandroidsdk.util.ResourceUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Displays a single room with messages.
 */
public class RoomActivity extends MXCActionBarActivity {

    public static final String EXTRA_ROOM_ID = "org.matrix.matrixandroidsdk.RoomActivity.EXTRA_ROOM_ID";

    private static final String TAG_FRAGMENT_MATRIX_MESSAGE_LIST = "org.matrix.androidsdk.RoomActivity.TAG_FRAGMENT_MATRIX_MESSAGE_LIST";
    private static final String TAG_FRAGMENT_MEMBERS_DIALOG = "org.matrix.androidsdk.RoomActivity.TAG_FRAGMENT_MEMBERS_DIALOG";
    private static final String TAG_FRAGMENT_INVITATION_MEMBERS_DIALOG = "org.matrix.androidsdk.RoomActivity.TAG_FRAGMENT_INVITATION_MEMBERS_DIALOG";
    private static final String TAG_FRAGMENT_ATTACHMENTS_DIALOG = "org.matrix.androidsdk.RoomActivity.TAG_FRAGMENT_ATTACHMENTS_DIALOG";

    private static final String LOG_TAG = "RoomActivity";
    private static final int TYPING_TIMEOUT_MS = 10000;

    private static final String PENDING_THUMBNAIL_URL = "PENDING_THUMBNAIL_URL";
    private static final String PENDING_MEDIA_URL = "PENDING_MEDIA_URL";
    private static final String PENDING_MIMETYPE = "PENDING_MIMETYPE";

    private static final String CAMERA_VALUE_TITLE = "attachment"; // Samsung devices need a filepath to write to or else won't return a Uri (!!!)

    // defines the command line operations
    // the user can write theses messages to perform some room events
    private static final String CMD_CHANGE_DISPLAY_NAME = "/nick";
    private static final String CMD_EMOTE = "/me";
    private static final String CMD_JOIN_ROOM = "/join";
    private static final String CMD_KICK_USER = "/kick";
    private static final String CMD_BAN_USER = "/ban";
    private static final String CMD_UNBAN_USER = "/unban";
    private static final String CMD_SET_USER_POWER_LEVEL = "/op";
    private static final String CMD_RESET_USER_POWER_LEVEL = "/deop";

    private static final int REQUEST_FILES = 0;
    private static final int TAKE_IMAGE = 0;

    private MatrixMessageListFragment mMatrixMessageListFragment;
    private MXSession mSession;
    private Room mRoom;

    private ImageButton mSendButton;
    private ImageButton mAttachmentButton;
    private EditText mEditText;

    private View mImagePreviewLayout;
    private ImageView mImagePreviewView;
    private ImageButton mImagePreviewButton;

    private String mPendingImageUrl;
    private String mPendingImediaUrl;
    private String mPendingMimeType;

    private String mLatestTakePictureCameraUri; // has to be String not Uri because of Serializable

    // typing event management
    private Timer mTypingTimer = null;
    private TimerTask mTypingTimerTask;
    private long  mLastTypingDate = 0;

    // sliding menu
    private final Integer[] mSlideMenuTitleIds = new Integer[]{
            R.string.action_room_info,
            R.string.action_members,
            R.string.action_invite_by_name,
            R.string.action_invite_by_list,
            R.string.action_leave,
            R.string.action_settings,
            R.string.send_bug_report,
    };

    private final Integer[] mSlideMenuResourceIds = new Integer[]{
            R.drawable.ic_material_description,  // R.string.action_room_info
            R.drawable.ic_material_group, // R.string.action_members
            R.drawable.ic_material_person_add, // R.string.option_invite_by_name
            R.drawable.ic_material_group_add, // R.string.option_invite_by_list
            R.drawable.ic_material_exit_to_app, // R.string.action_leave
            R.drawable.ic_material_settings, //  R.string.action_settings,
            R.drawable.ic_material_bug_report, // R.string.send_bug_report,
    };

    private MXEventListener mEventListener = new MXEventListener() {
        @Override
        public void onLiveEvent(final Event event, RoomState roomState) {
            RoomActivity.this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    // The various events that could possibly change the room title
                    if (Event.EVENT_TYPE_STATE_ROOM_NAME.equals(event.type)
                            || Event.EVENT_TYPE_STATE_ROOM_ALIASES.equals(event.type)
                            || Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(event.type)) {
                        setTitle(mRoom.getName(mSession.getCredentials().userId));
                    }
                    else if (Event.EVENT_TYPE_STATE_ROOM_TOPIC.equals(event.type)) {
                        Log.e(LOG_TAG, "Updating room topic.");
                        RoomState roomState = JsonUtils.toRoomState(event.content);
                        setTopic(roomState.topic);
                    }
                }
            });
        }

        @Override
        public void onRoomInitialSyncComplete(String roomId) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // set general room information
                    setTitle(mRoom.getName(mSession.getCredentials().userId));
                    setTopic(mRoom.getTopic());

                    mMatrixMessageListFragment.onInitialMessagesLoaded();
                }
            });
        }
    };

    /**
     * Laucnh the files selection intent
     */
    private void launchFileSelectionIntent() {
        Intent fileIntent = new Intent(Intent.ACTION_GET_CONTENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            fileIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        fileIntent.setType("*/*");
        startActivityForResult(fileIntent, REQUEST_FILES);
    }

    /**
     * Launch the camera
     */
    private void launchCamera() {
        Intent captureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        // the following is a fix for buggy 2.x devices
        Date date = new Date();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.TITLE, CAMERA_VALUE_TITLE + formatter.format(date));
        // The Galaxy S not only requires the name of the file to output the image to, but will also not
        // set the mime type of the picture it just took (!!!). We assume that the Galaxy S takes image/jpegs
        // so the attachment uploader doesn't freak out about there being no mimetype in the content database.
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        Uri dummyUri = null;
        try {
            dummyUri = RoomActivity.this.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        }
        catch (UnsupportedOperationException uoe) {
            Log.e(LOG_TAG, "Unable to insert camera URI into MediaStore.Images.Media.EXTERNAL_CONTENT_URI - no SD card? Attempting to insert into device storage.");
            try {
                dummyUri = RoomActivity.this.getContentResolver().insert(MediaStore.Images.Media.INTERNAL_CONTENT_URI, values);
            }
            catch (Exception e) {
                Log.e(LOG_TAG, "Unable to insert camera URI into internal storage. Giving up. "+e);
            }
        }
        catch (Exception e) {
            Log.e(LOG_TAG, "Unable to insert camera URI into MediaStore.Images.Media.EXTERNAL_CONTENT_URI. "+e);
        }
        if (dummyUri != null) {
            captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, dummyUri);
        }
        // Store the dummy URI which will be set to a placeholder location. When all is lost on samsung devices,
        // this will point to the data we're looking for.
        // Because Activities tend to use a single MediaProvider for all their intents, this field will only be the
        // *latest* TAKE_PICTURE Uri. This is deemed acceptable as the normal flow is to create the intent then immediately
        // fire it, meaning onActivityResult/getUri will be the next thing called, not another createIntentFor.
        RoomActivity.this.mLatestTakePictureCameraUri = dummyUri == null ? null : dummyUri.toString();

        startActivityForResult(captureIntent, TAKE_IMAGE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room);

        // define a sliding menu
        addSlidingMenu(mSlideMenuResourceIds, mSlideMenuTitleIds);

        Intent intent = getIntent();
        if (!intent.hasExtra(EXTRA_ROOM_ID)) {
            Log.e(LOG_TAG, "No room ID extra.");
            finish();
            return;
        }

        // the user has tapped on the "View" notification button
        if ((null != intent.getAction()) && (intent.getAction().startsWith(NotificationUtils.TAP_TO_VIEW_ACTION))) {
            // remove any pending notifications
            NotificationManager notificationsManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationsManager.cancelAll();
        }

        mPendingImageUrl = null;
        mPendingImediaUrl = null;
        mPendingMimeType = null;

        if (null != savedInstanceState) {
            if (savedInstanceState.containsKey(PENDING_THUMBNAIL_URL)) {
                mPendingImageUrl = savedInstanceState.getString(PENDING_THUMBNAIL_URL);
            }

            if (savedInstanceState.containsKey(PENDING_MEDIA_URL)) {
                mPendingImediaUrl = savedInstanceState.getString(PENDING_MEDIA_URL);
            }

            if (savedInstanceState.containsKey(PENDING_MIMETYPE)) {
                mPendingMimeType = savedInstanceState.getString(PENDING_MIMETYPE);
            }
        }

        String roomId = intent.getStringExtra(EXTRA_ROOM_ID);
        Log.i(LOG_TAG, "Displaying "+roomId);

        mEditText = (EditText)findViewById(R.id.editText_messageBox);

        mSendButton = (ImageButton)findViewById(R.id.button_send);
        mSendButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                // send the previewed image ?
                if (null != mPendingImageUrl) {
                    mMatrixMessageListFragment.uploadImageContent(mPendingImageUrl, mPendingImediaUrl, mPendingMimeType);
                    mPendingImageUrl = null;
                    mPendingImediaUrl = null;
                    mPendingMimeType = null;
                    manageSendMoreButtons();
                } else {
                    String body = mEditText.getText().toString();
                    sendMessage(body);
                    ConsoleLatestChatMessageCache.updateLatestMessage(RoomActivity.this, mRoom.getRoomId(), "");
                    mEditText.setText("");
                }
            }
        });

        mAttachmentButton = (ImageButton)findViewById(R.id.button_more);
        mAttachmentButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                FragmentManager fm = getSupportFragmentManager();
                IconAndTextDialogFragment fragment = (IconAndTextDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_ATTACHMENTS_DIALOG);

                if (fragment != null) {
                    fragment.dismissAllowingStateLoss();
                }

                final Integer[] messages = new Integer[]{
                        R.string.option_send_files,
                        R.string.option_take_photo,
                };

                final Integer[] icons = new Integer[]{
                        R.drawable.ic_material_file,  // R.string.option_send_files
                        R.drawable.ic_material_camera, // R.string.action_members
                };


                fragment = IconAndTextDialogFragment.newInstance(icons, messages);
                fragment.setOnClickListener(new IconAndTextDialogFragment.OnItemClickListener() {
                    @Override
                    public void onItemClick(IconAndTextDialogFragment dialogFragment, int position) {
                        Integer selectedVal = messages[position];

                        if (selectedVal ==  R.string.option_send_files) {
                            RoomActivity.this.launchFileSelectionIntent();
                        } else if (selectedVal == R.string.option_take_photo) {
                            RoomActivity.this.launchCamera();
                        }
                    }
                });

                fragment.show(fm, TAG_FRAGMENT_INVITATION_MEMBERS_DIALOG);
            }
        });

        mEditText.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(android.text.Editable s) {
                ConsoleLatestChatMessageCache.updateLatestMessage(RoomActivity.this, mRoom.getRoomId(), mEditText.getText().toString());
                handleTypingNotification(mEditText.getText().length() != 0);
                manageSendMoreButtons();
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        // make sure we're logged in.
        mSession = Matrix.getInstance(getApplicationContext()).getDefaultSession();
        if (mSession == null) {
            Log.e(LOG_TAG, "No MXSession.");
            finish();
            return;
        }
        CommonActivityUtils.resumeEventStream(this);

        mRoom = mSession.getDataHandler().getRoom(roomId);

        FragmentManager fm = getSupportFragmentManager();
        mMatrixMessageListFragment = (MatrixMessageListFragment) fm.findFragmentByTag(TAG_FRAGMENT_MATRIX_MESSAGE_LIST);

        if (mMatrixMessageListFragment == null) {
            // this fragment displays messages and handles all message logic
            mMatrixMessageListFragment = MatrixMessageListFragment.newInstance(mRoom.getRoomId());
            fm.beginTransaction().add(R.id.anchor_fragment_messages, mMatrixMessageListFragment, TAG_FRAGMENT_MATRIX_MESSAGE_LIST).commit();
        }

        // set general room information
        setTitle(mRoom.getName(mSession.getCredentials().userId));
        setTopic(mRoom.getTopic());

        // listen for room name or topic changes
        mRoom.addEventListener(mEventListener);

        // The error listener needs the current activity
        mSession.setFailureCallback(new ErrorListener(this));

        mImagePreviewLayout = findViewById(R.id.room_image_preview_layout);
        mImagePreviewView   = (ImageView)findViewById(R.id.room_image_preview);
        mImagePreviewButton = (ImageButton)findViewById(R.id.room_image_preview_cancel_button);

        // the user cancels the image selection
        mImagePreviewButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mPendingImageUrl = null;
                mPendingImediaUrl = null;
                mPendingMimeType = null;
                manageSendMoreButtons();
            }
        });

        manageSendMoreButtons();
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState);

        if (null != mPendingImageUrl) {
            savedInstanceState.putString(PENDING_THUMBNAIL_URL, mPendingImageUrl);
        }

        if (null != mPendingImediaUrl) {
            savedInstanceState.putString(PENDING_MEDIA_URL, mPendingImediaUrl);
        }

        if (null != mPendingMimeType) {
            savedInstanceState.putString(PENDING_MIMETYPE, mPendingMimeType);
        }
    }

    /**
     *
     */
    private void manageSendMoreButtons() {
        boolean hasText = mEditText.getText().length() > 0;
        boolean hasPreviewedMedia = (null != mPendingImageUrl);

        if (hasPreviewedMedia) {
            ConsoleMediasCache.loadBitmap(mImagePreviewView, mPendingImageUrl, 0, mPendingMimeType);
        }

        mImagePreviewLayout.setVisibility(hasPreviewedMedia ? View.VISIBLE : View.GONE);
        mEditText.setVisibility(hasPreviewedMedia ? View.INVISIBLE : View.VISIBLE);

        mSendButton.setVisibility((hasText || hasPreviewedMedia) ? View.VISIBLE : View.INVISIBLE);
        mAttachmentButton.setVisibility((hasText || hasPreviewedMedia)  ? View.INVISIBLE : View.VISIBLE);
    }

    @Override
    public void onDestroy() {
        // add sanity check
        // the activity creation could have been cancelled because the roomId was missing
        if ((null != mRoom) && (null != mEventListener)) {
            mRoom.removeEventListener(mEventListener);
        }

        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ViewedRoomTracker.getInstance().setViewedRoomId(null);
        MyPresenceManager.getInstance(this).advertiseUnavailableAfterDelay();
        // warn other member that the typing is ended
        cancelTypingNotification();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ViewedRoomTracker.getInstance().setViewedRoomId(mRoom.getRoomId());
        MyPresenceManager.getInstance(this).advertiseOnline();

        EventStreamService.cancelNotificationsForRoomId(mRoom.getRoomId());

        String cachedText = ConsoleLatestChatMessageCache.getLatestText(this, mRoom.getRoomId());

        if (!cachedText.equals(mEditText.getText())) {
            mEditText.setText("");
            mEditText.append(cachedText);
        }
    }

    private void setTopic(String topic) {
        if (null !=  this.getSupportActionBar()) {
            this.getSupportActionBar().setSubtitle(topic);
        }
    }

    /**
     * check if the text message is an IRC command.
     * If it is an IRC command, it is executed
     * @param body
     * @return true if body defines an IRC command
     */
    private boolean manageIRCCommand(String body) {
        boolean isIRCCmd = false;

        // check if it has the IRC marker
        if ((null != body) && (body.startsWith("/"))) {
            MXSession session = Matrix.getInstance(this).getDefaultSession();

            final ApiCallback callback = new SimpleApiCallback<Void>(this) {
                @Override
                public void onMatrixError(MatrixError e) {
                    if (MatrixError.FORBIDDEN.equals(e.errcode)) {
                        Toast.makeText(RoomActivity.this, e.error, Toast.LENGTH_LONG).show();
                    }
                }
            };

            if (body.startsWith(CMD_CHANGE_DISPLAY_NAME)) {
                isIRCCmd = true;

                String newDisplayname = body.substring(CMD_CHANGE_DISPLAY_NAME.length()).trim();

                if (newDisplayname.length() > 0) {
                    MyUser myUser = session.getMyUser();

                    myUser.updateDisplayName(newDisplayname, callback);
                }
            } else if (body.startsWith(CMD_EMOTE)) {
                isIRCCmd = true;

                String message = body.substring(CMD_EMOTE.length()).trim();

                if (message.length() > 0) {
                    mMatrixMessageListFragment.sendEmote(message);
                }
            } else if (body.startsWith(CMD_JOIN_ROOM)) {
                isIRCCmd = true;

                String roomAlias = body.substring(CMD_JOIN_ROOM.length()).trim();

                if (roomAlias.length() > 0) {
                    session.joinRoom(roomAlias,new SimpleApiCallback<String>(this) {

                        @Override
                        public void onSuccess(String roomId) {
                            if (null != roomId) {
                                CommonActivityUtils.goToRoomPage(roomId, RoomActivity.this);
                            }
                        }
                    });
                }
            } else if (body.startsWith(CMD_KICK_USER)) {
                isIRCCmd = true;

                String params = body.substring(CMD_KICK_USER.length()).trim();
                String[] paramsList = params.split(" ");

                String kickedUserID = paramsList[0];

                if (kickedUserID.length() > 0) {
                    mRoom.kick(kickedUserID, callback);
                }
            } else if (body.startsWith(CMD_BAN_USER)) {
                isIRCCmd = true;

                String params = body.substring(CMD_BAN_USER.length()).trim();
                String[] paramsList = params.split(" ");

                String bannedUserID = paramsList[0];
                String reason = params.substring(bannedUserID.length()).trim();

                if (bannedUserID.length() > 0) {
                    mRoom.ban(bannedUserID, reason, callback);
                }
            } else if (body.startsWith(CMD_UNBAN_USER)) {
                isIRCCmd = true;

                String params = body.substring(CMD_UNBAN_USER.length()).trim();
                String[] paramsList = params.split(" ");

                String unbannedUserID = paramsList[0];

                if (unbannedUserID.length() > 0) {
                    mRoom.unban(unbannedUserID, callback);
                }
            } else if (body.startsWith(CMD_SET_USER_POWER_LEVEL)) {
                isIRCCmd = true;

                String params = body.substring(CMD_SET_USER_POWER_LEVEL.length()).trim();
                String[] paramsList = params.split(" ");

                String userID = paramsList[0];
                String powerLevelsAsString  = params.substring(userID.length()).trim();

                try {
                    if ((userID.length() > 0) && (powerLevelsAsString.length() > 0)) {
                        mRoom.updateUserPowerLevels(userID, Integer.parseInt(powerLevelsAsString), callback);
                    }
                } catch(Exception e){

                }
            } else if (body.startsWith(CMD_RESET_USER_POWER_LEVEL)) {
                isIRCCmd = true;

                String params = body.substring(CMD_RESET_USER_POWER_LEVEL.length()).trim();
                String[] paramsList = params.split(" ");

                String userID = paramsList[0];

                if (userID.length() > 0) {
                    mRoom.updateUserPowerLevels(userID, 0, callback);
                }
            }
        }

        return isIRCCmd;
    }

    private void sendMessage(String body) {
        if (!TextUtils.isEmpty(body)) {
            if (!manageIRCCommand(body)) {
                mMatrixMessageListFragment.sendTextMessage(body);
            }
        }
    }

    /**
     * Send a list of images from their URIs
     * @param mediaUris the media URIs
     */
    private void sendMedias(ArrayList<Uri> mediaUris) {

        final int mediaCount = mediaUris.size();

        for(Uri anUri : mediaUris) {
            final Uri mediaUri = anUri;

            RoomActivity.this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    ResourceUtils.Resource resource = ResourceUtils.openResource(RoomActivity.this, mediaUri);
                    if (resource == null) {
                        Toast.makeText(RoomActivity.this,
                                getString(R.string.message_failed_to_upload),
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    // save the file in the filesystem
                    String mediaUrl = ConsoleMediasCache.saveMedia(resource.contentStream, RoomActivity.this, null, resource.mimeType);
                    String mimeType = resource.mimeType;
                    Boolean isManaged = false;

                    if ((null != resource.mimeType) && resource.mimeType.startsWith("image/")) {
                        // manage except if there is an error
                        isManaged = true;

                        // try to retrieve the gallery thumbnail
                        // if the image comes from the gallery..
                        Bitmap thumbnailBitmap = null;

                        try {
                            ContentResolver resolver = getContentResolver();
                            List uriPath = mediaUri.getPathSegments();
                            long imageId = Long.parseLong((String) (uriPath.get(uriPath.size() - 1)));

                            thumbnailBitmap = MediaStore.Images.Thumbnails.getThumbnail(resolver, imageId, MediaStore.Images.Thumbnails.MINI_KIND, null);
                        } catch (Exception e) {

                        }

                        // no thumbnail has been found or the mimetype is unknown
                        if (null == thumbnailBitmap) {
                            // need to decompress the high res image
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                            resource = ResourceUtils.openResource(RoomActivity.this, mediaUri);

                            // get the full size bitmap
                            Bitmap fullSizeBitmap = BitmapFactory.decodeStream(resource.contentStream, null, options);

                            // create a thumbnail bitmap if there is none
                            if (null == thumbnailBitmap) {
                                if (fullSizeBitmap != null) {
                                    double fullSizeWidth = fullSizeBitmap.getWidth();
                                    double fullSizeHeight = fullSizeBitmap.getHeight();

                                    double thumbnailWidth = mMatrixMessageListFragment.getMaxThumbnailWith();
                                    double thumbnailHeight = mMatrixMessageListFragment.getMaxThumbnailHeight();

                                    if (fullSizeWidth > fullSizeHeight) {
                                        thumbnailHeight = thumbnailWidth * fullSizeHeight / fullSizeWidth;
                                    } else {
                                        thumbnailWidth = thumbnailHeight * fullSizeWidth / fullSizeHeight;
                                    }

                                    try {
                                        thumbnailBitmap = Bitmap.createScaledBitmap(fullSizeBitmap, (int) thumbnailWidth, (int) thumbnailHeight, false);
                                    } catch (OutOfMemoryError ex) {
                                    }
                                }
                            }

                            // unknown mimetype
                            if ((null == mimeType) || (mimeType.startsWith("image/"))) {
                                try {
                                    if (null != fullSizeBitmap) {
                                        Uri uri = Uri.parse(mediaUrl);
                                        try {
                                            ConsoleMediasCache.saveBitmap(fullSizeBitmap, RoomActivity.this, uri.getPath());
                                        } catch (OutOfMemoryError ex) {
                                        }

                                        // the images are save in jpeg format
                                        mimeType = "image/jpeg";
                                    } else {
                                        isManaged = false;
                                    }

                                    resource.contentStream.close();

                                } catch (Exception e) {
                                    isManaged = false;
                                }
                            }

                            // reduce the memory consumption
                            if (null  != fullSizeBitmap) {
                                fullSizeBitmap.recycle();
                                System.gc();
                            }
                        }

                        String thumbnailURL = ConsoleMediasCache.saveBitmap(thumbnailBitmap, RoomActivity.this, null);

                        if (null != thumbnailBitmap) {
                            thumbnailBitmap.recycle();
                        }

                        // is the image content valid ?
                        if (isManaged  && (null != thumbnailURL)) {

                            // if there is only one image
                            if (mediaCount == 1) {
                                // display an image preview before sending it
                                mPendingImageUrl = thumbnailURL;
                                mPendingImediaUrl = mediaUrl;
                                mPendingMimeType = mimeType;

                                mMatrixMessageListFragment.scrollToBottom();

                                manageSendMoreButtons();
                            } else {
                                mMatrixMessageListFragment.uploadImageContent(thumbnailURL, mediaUrl, mimeType);
                            }
                        }
                    }

                    // default behaviour
                    if ((!isManaged) && (null != mediaUrl)) {
                        String filename = "A file";

                        try {
                            ContentResolver resolver = getContentResolver();
                            List uriPath = mediaUri.getPathSegments();
                            filename = "";

                            if (mediaUri.toString().startsWith("content://")) {
                                Cursor cursor = null;
                                try {
                                    cursor = RoomActivity.this.getContentResolver().query(mediaUri, null, null, null, null);
                                    if (cursor != null && cursor.moveToFirst()) {
                                        filename = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                                    }
                                } catch (Exception e) {

                                }
                                finally {
                                    cursor.close();
                                }

                                if (filename.length() == 0) {
                                    filename = (String)uriPath.get(uriPath.size() - 1);
                                }
                            }

                        } catch (Exception e) {

                        }

                        mMatrixMessageListFragment.uploadMediaContent(mediaUrl, mimeType, filename);
                    }
                }
            });
        }
    }

    @SuppressLint("NewApi")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {
            if ((requestCode == REQUEST_FILES) || (requestCode == TAKE_IMAGE)) {
                ArrayList<Uri> uris = new ArrayList<Uri>();

                if (null != data) {
                    ClipData clipData = null;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        clipData = data.getClipData();
                    }

                    // multiple data
                    if (null != clipData) {
                        int count = clipData.getItemCount();

                        for (int i = 0; i < count; i++) {
                            ClipData.Item item = clipData.getItemAt(i);
                            Uri uri = item.getUri();

                            if (null != uri) {
                                uris.add(uri);
                            }
                        }

                    } else if (null != data.getData()) {
                        uris.add(data.getData());
                    }
                } else {
                    uris.add( mLatestTakePictureCameraUri == null ? null : Uri.parse(mLatestTakePictureCameraUri));
                    mLatestTakePictureCameraUri = null;
                }

                if (0 != uris.size()) {
                    sendMedias(uris);
                }
            }
        }
    }

    /**
     * send a typing event notification
     * @param isTyping typing param
     */
    void handleTypingNotification(boolean isTyping) {
        int notificationTimeoutMS = -1;
        if (isTyping) {
            // Check whether a typing event has been already reported to server (We wait for the end of the local timout before considering this new event)
            if (null != mTypingTimer) {
                // Refresh date of the last observed typing
                System.currentTimeMillis();
                mLastTypingDate = System.currentTimeMillis();
                return;
            }

            int timerTimeoutInMs = TYPING_TIMEOUT_MS;

            if (0 != mLastTypingDate) {
                long lastTypingAge = System.currentTimeMillis() - mLastTypingDate;
                if (lastTypingAge < timerTimeoutInMs) {
                    // Subtract the time interval since last typing from the timer timeout
                    timerTimeoutInMs -= lastTypingAge;
                } else {
                    timerTimeoutInMs = 0;
                }
            } else {
                // Keep date of this typing event
                mLastTypingDate = System.currentTimeMillis();
            }

            if (timerTimeoutInMs > 0) {
                mTypingTimer = new Timer();
                mTypingTimerTask = new TimerTask() {
                    public void run() {
                        if (mTypingTimerTask != null) {
                            mTypingTimerTask.cancel();
                            mTypingTimerTask = null;
                        }

                        if (mTypingTimer != null) {
                            mTypingTimer.cancel();
                            mTypingTimer = null;
                        }
                        // Post a new typing notification
                        RoomActivity.this.handleTypingNotification(0 != mLastTypingDate);
                    }
                };
                mTypingTimer.schedule(mTypingTimerTask, TYPING_TIMEOUT_MS);

                // Compute the notification timeout in ms (consider the double of the local typing timeout)
                notificationTimeoutMS = TYPING_TIMEOUT_MS * 2;
            } else {
                // This typing event is too old, we will ignore it
                isTyping = false;
            }
        }
        else {
            // Cancel any typing timer
            if (mTypingTimerTask != null) {
                mTypingTimerTask.cancel();
                mTypingTimerTask = null;
            }

            if (mTypingTimer != null) {
                mTypingTimer.cancel();
                mTypingTimer = null;
            }
            // Reset last typing date
            mLastTypingDate = 0;
        }

        final boolean typingStatus = isTyping;

        mRoom.sendTypingNotification(typingStatus, notificationTimeoutMS, new SimpleApiCallback<Void>(RoomActivity.this) {
            @Override
            public void onSuccess(Void info) {
                // Reset last typing date
                mLastTypingDate = 0;
            }

            @Override
            public void onNetworkError(Exception e) {
                if (mTypingTimerTask != null) {
                    mTypingTimerTask.cancel();
                    mTypingTimerTask = null;
                }

                if (mTypingTimer != null) {
                    mTypingTimer.cancel();
                    mTypingTimer = null;
                }
                // do not send again
                // assume that the typing event is optional
            }
        });
    }

    void cancelTypingNotification() {
        if (0 != mLastTypingDate) {
            if (mTypingTimerTask != null) {
                mTypingTimerTask.cancel();
                mTypingTimerTask = null;
            }
            if (mTypingTimer != null) {
                mTypingTimer.cancel();
                mTypingTimer = null;
            }

            mLastTypingDate = 0;

            mRoom.sendTypingNotification(false, -1, new SimpleApiCallback<Void>(RoomActivity.this) {
            });
        }
    }

    /**
     * Run the dedicated sliding menu action
     * @param position selected menu entry
     */
    @Override
    protected void selectDrawItem(int position) {
        super.selectDrawItem(position);

        final int id = (position == 0) ? R.string.action_settings : mSlideMenuTitleIds[position - 1];
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {

                if (id == R.string.action_invite_by_list) {
                    final MXSession session = Matrix.getInstance(getApplicationContext()).getDefaultSession();
                    if (session != null) {
                        FragmentManager fm = getSupportFragmentManager();

                        MembersInvitationDialogFragment fragment = (MembersInvitationDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_INVITATION_MEMBERS_DIALOG);
                        if (fragment != null) {
                            fragment.dismissAllowingStateLoss();
                        }
                        fragment = MembersInvitationDialogFragment.newInstance(mRoom.getRoomId());
                        fragment.show(fm, TAG_FRAGMENT_INVITATION_MEMBERS_DIALOG);
                    }
                } else if (id == R.string.action_invite_by_name) {
                    AlertDialog alert = CommonActivityUtils.createEditTextAlert(RoomActivity.this, RoomActivity.this.getResources().getString(R.string.title_activity_invite_user), RoomActivity.this.getResources().getString(R.string.room_creation_participants_hint), null, new CommonActivityUtils.OnSubmitListener() {
                        @Override
                        public void onSubmit(final String text) {
                            if (TextUtils.isEmpty(text)) {
                                return;
                            }

                            // get the user suffix
                            String userID = mSession.getCredentials().userId;
                            String homeServerSuffix = userID.substring(userID.indexOf(":"), userID.length());

                            ArrayList<String> userIDsList = CommonActivityUtils.parseUserIDsList(text, homeServerSuffix);

                            if (userIDsList.size() > 0) {
                                mRoom.invite(userIDsList, new SimpleApiCallback<Void>(RoomActivity.this) {
                                    @Override
                                    public void onSuccess(Void info) {
                                        Toast.makeText(getApplicationContext(), "Sent invite to " + text.trim() + ".", Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        }

                        @Override
                        public void onCancelled() {

                        }
                    });

                    alert.show();
                } else if (id ==  R.string.action_members) {
                    FragmentManager fm = getSupportFragmentManager();

                    RoomMembersDialogFragment fragment = (RoomMembersDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_MEMBERS_DIALOG);
                    if (fragment != null) {
                        fragment.dismissAllowingStateLoss();
                    }
                    fragment = RoomMembersDialogFragment.newInstance(mRoom.getRoomId());
                    fragment.show(fm, TAG_FRAGMENT_MEMBERS_DIALOG);
                } else if (id ==  R.string.action_room_info) {
                    Intent startRoomInfoIntent = new Intent(RoomActivity.this, RoomInfoActivity.class);
                    startRoomInfoIntent.putExtra(EXTRA_ROOM_ID, mRoom.getRoomId());
                    startActivity(startRoomInfoIntent);
                } else if (id ==  R.string.action_leave) {
                    MXSession session = Matrix.getInstance(getApplicationContext()).getDefaultSession();
                    if (session != null) {
                        mRoom.leave(new SimpleApiCallback<Void>(RoomActivity.this) {

                        });
                        RoomActivity.this.finish();
                    }
                } else if (id ==  R.string.action_settings) {
                    RoomActivity.this.startActivity(new Intent(RoomActivity.this, SettingsActivity.class));
                } else if (id ==  R.string.send_bug_report) {
                    RageShake.getInstance().sendBugReport();
                }
            }
        });
    }
}
