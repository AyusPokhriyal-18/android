/*
 * Copyright (c) 2014 Amahi
 *
 * This file is part of Amahi.
 *
 * Amahi is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Amahi is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Amahi. If not, see <http ://www.gnu.org/licenses/>.
 */

package org.amahi.anywhere.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.RemoteControlClient;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;

import com.squareup.otto.Subscribe;

import org.amahi.anywhere.AmahiApplication;
import org.amahi.anywhere.R;
import org.amahi.anywhere.bus.AudioControlPauseEvent;
import org.amahi.anywhere.bus.AudioControlPlayEvent;
import org.amahi.anywhere.bus.AudioControlPlayPauseEvent;
import org.amahi.anywhere.bus.AudioMetadataRetrievedEvent;
import org.amahi.anywhere.bus.BusProvider;
import org.amahi.anywhere.receiver.AudioReceiver;
import org.amahi.anywhere.server.client.ServerClient;
import org.amahi.anywhere.server.model.ServerFile;
import org.amahi.anywhere.server.model.ServerShare;
import org.amahi.anywhere.task.AudioMetadataRetrievingTask;
import org.amahi.anywhere.util.AudioMetadataFormatter;

import java.io.IOException;

import javax.inject.Inject;

public class AudioService extends Service implements MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener
{
	private static final int AUDIO_PLAYER_NOTIFICATION = 42;

	private MediaPlayer audioPlayer;
	private RemoteControlClient audioPlayerRemote;

	private ServerShare audioShare;
	private ServerFile audioFile;

	@Inject
	ServerClient serverClient;

	@Override
	public IBinder onBind(Intent intent) {
		return new AudioServiceBinder(this);
	}

	@Override
	public void onCreate() {
		super.onCreate();

		setUpInjections();

		setUpBus();

		setUpAudioPlayer();
		setUpAudioPlayerRemote();
	}

	private void setUpInjections() {
		AmahiApplication.from(this).inject(this);
	}

	private void setUpBus() {
		BusProvider.getBus().register(this);
	}

	private void setUpAudioPlayer() {
		audioPlayer = new MediaPlayer();

		audioPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
		audioPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);

		audioPlayer.setOnCompletionListener(this);
	}

	private void setUpAudioPlayerRemote() {
		AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		ComponentName audioReceiver = new ComponentName(getPackageName(), AudioReceiver.class.getName());

		Intent audioIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
		audioIntent.setComponent(audioReceiver);
		PendingIntent audioPendingIntent = PendingIntent.getBroadcast(this, 0, audioIntent, 0);

		audioPlayerRemote = new RemoteControlClient(audioPendingIntent);
		audioPlayerRemote.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE);
		audioPlayerRemote.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);

		audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
		audioManager.registerMediaButtonEventReceiver(audioReceiver);
		audioManager.registerRemoteControlClient(audioPlayerRemote);
	}

	public boolean isAudioStarted() {
		return (audioShare != null) && (audioFile != null);
	}

	public void startAudio(ServerShare audioShare, ServerFile audioFile, MediaPlayer.OnPreparedListener audioListener) {
		this.audioShare = audioShare;
		this.audioFile = audioFile;

		setUpAudioPlayback(audioListener);
		setUpAudioMetadata();
	}

	private void setUpAudioPlayback(MediaPlayer.OnPreparedListener audioListener) {
		try {
			audioPlayer.setDataSource(this, getAudioUri());
			audioPlayer.setOnPreparedListener(audioListener);
			audioPlayer.prepareAsync();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private Uri getAudioUri() {
		return serverClient.getFileUri(audioShare, audioFile);
	}

	private void setUpAudioMetadata() {
		AudioMetadataRetrievingTask.execute(getAudioUri());
	}

	@Subscribe
	public void onAudioMetadataRetrieved(AudioMetadataRetrievedEvent event) {
		AudioMetadataFormatter audioMetadataFormatter = new AudioMetadataFormatter(
			event.getAudioTitle(), event.getAudioArtist(), event.getAudioAlbum());

		setUpAudioPlayerRemote(audioMetadataFormatter, event.getAudioAlbumArt());
		setUpAudioPlayerNotification(audioMetadataFormatter, event.getAudioAlbumArt());
	}

	private void setUpAudioPlayerRemote(AudioMetadataFormatter audioMetadataFormatter, Bitmap audioAlbumArt) {
		audioPlayerRemote
			.editMetadata(true)
			.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, audioMetadataFormatter.getAudioTitle(audioFile))
			.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, audioMetadataFormatter.getAudioSubtitle(audioShare))
			.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, getAudioPlayerRemoteArtwork(audioAlbumArt))
			.apply();
	}

	private Bitmap getAudioPlayerRemoteArtwork(Bitmap audioAlbumArt) {
		if (audioAlbumArt == null) {
			return null;
		}

		Bitmap.Config artworkConfig = audioAlbumArt.getConfig();

		if (artworkConfig == null) {
			artworkConfig = Bitmap.Config.ARGB_8888;
		}

		return audioAlbumArt.copy(artworkConfig, false);
	}

	private void setUpAudioPlayerNotification(AudioMetadataFormatter audioMetadataFormatter, Bitmap audioAlbumArt) {
		Notification notification = new NotificationCompat.Builder(this)
			.setContentTitle(audioMetadataFormatter.getAudioTitle(audioFile))
			.setContentText(audioMetadataFormatter.getAudioSubtitle(audioShare))
			.setSmallIcon(getAudioPlayerNotificationIcon())
			.setLargeIcon(getAudioPlayerNotificationArtwork(audioAlbumArt))
			.setOngoing(true)
			.setWhen(0)
			.build();

		startForeground(AUDIO_PLAYER_NOTIFICATION, notification);
	}

	private int getAudioPlayerNotificationIcon() {
		return R.drawable.ic_notification_audio;
	}

	private Bitmap getAudioPlayerNotificationArtwork(Bitmap audioAlbumArt) {
		int iconHeight = (int) getResources().getDimension(android.R.dimen.notification_large_icon_height);
		int iconWidth = (int) getResources().getDimension(android.R.dimen.notification_large_icon_width);

		if (audioAlbumArt == null) {
			return null;
		}

		return Bitmap.createScaledBitmap(audioAlbumArt, iconWidth, iconHeight, false);
	}

	public MediaPlayer getAudioPlayer() {
		return audioPlayer;
	}

	@Subscribe
	public void onAudioControlPlayPause(AudioControlPlayPauseEvent event) {
		if (audioPlayer.isPlaying()) {
			pauseAudio();
		} else {
			playAudio();
		}
	}

	@Subscribe
	public void onAudioControlPlay(AudioControlPlayEvent event) {
		playAudio();
	}

	@Subscribe
	public void onAudioControlPause(AudioControlPauseEvent event) {
		pauseAudio();
	}

	public void playAudio() {
		audioPlayer.start();

		audioPlayerRemote.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
	}

	public void pauseAudio() {
		audioPlayer.pause();

		audioPlayerRemote.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
	}

	@Override
	public void onAudioFocusChange(int audioFocus) {
		switch (audioFocus) {
			case AudioManager.AUDIOFOCUS_GAIN:
				if (isAudioPlaying()) {
					setUpAudioVolume();
				} else {
					playAudio();
				}
				break;

			case AudioManager.AUDIOFOCUS_LOSS:
			case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
				if (isAudioPlaying()) {
					pauseAudio();
				}
				break;

			case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
				tearDownAudioVolume();
				break;

			default:
				break;
		}
	}

	private boolean isAudioPlaying() {
		try {
			return isAudioStarted() && audioPlayer.isPlaying();
		} catch (IllegalStateException e) {
			return false;
		}
	}

	private void setUpAudioVolume() {
		audioPlayer.setVolume(1.0f, 1.0f);
	}

	private void tearDownAudioVolume() {
		audioPlayer.setVolume(0.3f, 0.3f);
	}

	@Override
	public void onCompletion(MediaPlayer audioPlayer) {
		pauseAudio();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		tearDownBus();

		tearDownAudioPlayer();
		tearDownAudioPlayerRemote();
		tearDownAudioPlayerNotification();
	}

	private void tearDownBus() {
		BusProvider.getBus().unregister(this);
	}

	private void tearDownAudioPlayer() {
		audioPlayer.reset();
		audioPlayer.release();
	}

	private void tearDownAudioPlayerRemote() {
		AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		ComponentName audioReceiver = new ComponentName(getPackageName(), AudioReceiver.class.getName());

		audioManager.unregisterMediaButtonEventReceiver(audioReceiver);
		audioManager.unregisterRemoteControlClient(audioPlayerRemote);
	}

	private void tearDownAudioPlayerNotification() {
		stopForeground(true);
	}

	public static final class AudioServiceBinder extends Binder
	{
		private final AudioService audioService;

		public AudioServiceBinder(AudioService audioService) {
			this.audioService = audioService;
		}

		public AudioService getAudioService() {
			return audioService;
		}
	}
}
