This folder is not used directly by AndroidStudio. If you make changes here, make sure to copy them to `app/src/main/res/drawable`. For SVG files, this means converting them into XML files. In Android Studio, this can be done by doing File > New > Vector Asset > Local File.

Vector assets that are in the `drawable` folder but not in this folder are built-in to Android Studio and were created by doing File > New > Vector Asset > Cip Art.

The `pcloud_icon.png` is used in PCloud's developer console as the icon that should be used when logging in to PCloud from the Photos app, and `google_play_icon.png` is used on Google Play. They're not used anywhere in the app's source code directly.
