package org.thoughtcrime.securesms.components;


import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.AttributeSet;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.mms.VideoSlide;
import org.thoughtcrime.securesms.video.VideoPlayer;

import java.io.IOException;

public class MediaView extends FrameLayout {

  private ZoomingImageView imageView;
  private VideoPlayer      videoView;

  public MediaView(@NonNull Context context) {
    super(context);
    initialize();
  }

  public MediaView(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public MediaView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  public MediaView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    initialize();
  }

  private void initialize() {
    inflate(getContext(), R.layout.media_view, this);

    this.imageView = findViewById(R.id.image);
    this.videoView = findViewById(R.id.video_player);
  }

  public void set(@NonNull MasterSecret masterSecret,
                  @NonNull GlideRequests glideRequests,
                  @NonNull Window window,
                  @NonNull Uri source,
                  @NonNull String mediaType,
                  long size,
                  boolean autoplay)
      throws IOException
  {
    if (mediaType.startsWith("image/")) {
      imageView.setVisibility(View.VISIBLE);
      videoView.setVisibility(View.GONE);
      imageView.setImageUri(masterSecret, glideRequests, source, mediaType);
    } else if (mediaType.startsWith("video/")) {
      imageView.setVisibility(View.GONE);
      videoView.setVisibility(View.VISIBLE);
      videoView.setWindow(window);
      videoView.setVideoSource(masterSecret, new VideoSlide(getContext(), source, size), autoplay);
    } else {
      throw new IOException("Unsupported media type: " + mediaType);
    }
  }

  public void pause() {
    this.videoView.pause();
  }

  public void cleanup() {
    this.imageView.cleanup();
    this.videoView.cleanup();
  }
}
