# Photos
This is photos app for Android. It has the following features:

- Allows you to edit or remove the location at which a photo was taken, even if the location was added by the camera (unlike Google Photos which will only let you add a location if it wasn't already added by the camera).
- Allows you to see all your photos from multiple storage services (Google Drive, OneDrive, PCloud) all in one place.

## Installation
This app can be downloaded on Google Play [here](https://play.google.com/store/apps/details?id=io.github.gustavlindberg99.photos).

## Usage
### View photo details and edit photo
To view details about a photo and/or edit it, long click on the photo. Here you can rotate the photo, delete it, view information such as the date it was taken, choose what storage services to upload it to (see below), and edit or remove the location at which it was taken.

You can also see a map of the locations where all your photos were taken by clicking on the map icon on the top left.

### Storage services
This app allows you to view photos that are stored in Google Drive, OneDrive, and PCloud. To sign in to one or more of these services, click on the settings icon on the top right, then enable "Use &lt;storage service&gt;". This will prompt you to log in to that service.

Once you're logged in to a storage service, you can enable automatic uploading, which means that whenever you take a new photo it will be automatically uploaded to that storage service. You can also choose which folder to use as your "Photos" folder. This is the folder where new photos will be uploaded, and this folder and its subfolders are where the app will search for existing photos. To specify the root folder (meaning this app will search for photos everywhere in your account), leave that field blank. To specify a subfolder, separate each folder by a slash (for example `Pictures/Photos` for a folder named "Photos" inside a folder named "Pictures").
