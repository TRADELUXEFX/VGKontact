<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/white">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:background="@color/white">

        <!-- ==== GREEN HEADER ==== -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:gravity="center_horizontal"
            android:background="@drawable/header_gradient"
            android:paddingTop="56dp"
            android:paddingStart="28dp"
            android:paddingEnd="28dp"
            android:paddingBottom="32dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Verify Your"
                android:textSize="15sp"
                android:alpha="0.92"
                android:gravity="center"
                android:textColor="@color/white" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="WhatsApp Number"
                android:textSize="26sp"
                android:textStyle="bold"
                android:layout_marginTop="2dp"
                android:gravity="center"
                android:textColor="@color/white" />

        </LinearLayout>

        <!-- ==== WHITE BODY ==== -->
        <ScrollView
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:paddingStart="20dp"
                android:paddingEnd="20dp"
                android:paddingTop="24dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:background="@drawable/card_background"
                    android:padding="20dp">

                    <TextView
                        android:id="@+id/subtitleText"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="Enter the 6-digit code sent to your number"
                        android:textSize="13sp"
                        android:textColor="@color/text_muted"
                        android:gravity="center"
                        android:layout_marginBottom="20dp" />

                    <TextView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="VERIFICATION CODE"
                        android:textSize="11sp"
                        android:textStyle="bold"
                        android:textAllCaps="true"
                        android:letterSpacing="0.05"
                        android:textColor="@color/text_muted"
                        android:gravity="center"
                        android:layout_marginBottom="8dp" />

                    <com.google.android.material.textfield.TextInputLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:hint="000000"
                        android:textColorHint="@color/text_muted"
                        app:hintTextColor="@color/vg_green_dark"
                        app:boxBackgroundMode="outline"
                        app:boxBackgroundColor="@color/vg_field_bg"
                        app:boxStrokeColor="@color/vg_green"
                        app:boxStrokeWidth="1.5dp"
                        app:boxCornerRadiusTopStart="14dp"
                        app:boxCornerRadiusTopEnd="14dp"
                        app:boxCornerRadiusBottomStart="14dp"
                        app:boxCornerRadiusBottomEnd="14dp"
                        style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox">

                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/otpInput"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:gravity="center"
                            android:inputType="number"
                            android:maxLength="6"
                            android:letterSpacing="0.3"
                            android:textSize="20sp"
                            android:textStyle="bold"
                            android:textColor="@color/vg_dark"
                            android:textColorHint="@color/text_muted"
                            android:includeFontPadding="false"
                            android:paddingStart="14dp"
                            android:paddingEnd="14dp"
                            android:paddingTop="14dp"
                            android:paddingBottom="14dp" />

                    </com.google.android.material.textfield.TextInputLayout>

                </LinearLayout>

            </LinearLayout>

        </ScrollView>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="20dp">

            <FrameLayout
                android:layout_width="match_parent"
                android:layout_height="56dp">

                <Button
                    android:id="@+id/verifyButton"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:text="Verify"
                    android:textSize="16sp"
                    android:textStyle="bold"
                    android:textAllCaps="false"
                    android:backgroundTint="@color/vg_green"
                    android:textColor="@color/white"
                    android:gravity="center"
                    android:drawableEnd="@drawable/ic_arrow_forward"
                    android:drawablePadding="8dp"
                    app:cornerRadius="16dp" />

                <ProgressBar
                    android:id="@+id/progressBar"
                    android:layout_width="24dp"
                    android:layout_height="24dp"
                    android:layout_gravity="center"
                    android:indeterminateTint="@color/white"
                    android:visibility="gone" />

            </FrameLayout>

            <TextView
                android:id="@+id/resendText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="I've sent it - Verify"
                android:textSize="15sp"
                android:textColor="@color/text_muted"
                android:textStyle="bold"
                android:gravity="center"
                android:background="@drawable/card_background"
                android:padding="14dp"
                android:layout_marginTop="12dp"
                android:enabled="false"
                android:alpha="0.5" />

        </LinearLayout>

    </LinearLayout>

</FrameLayout>
