# Activity, Task, Intent Behaviour

This document will describe how activities, tasks, and intents are handled in the Psiphon Android app. This is somewhat complex and non-standard, and it's not easy to see what's happening or guess what will happen by reading the code. This should be a living document to assist developers in understanding what we want and how we're doing it.

## Requirements

* The status activity and browser activity **must** be accessible within two touches.

* The browser **must** not reload its tabs every time it is foregrounded. It **may** reload after inactivity-destruction by the system.

* The browser **must** not lose its open tabs every time it is foregrounded, even after inactivity-destruction by the system.

* The browser **must** not lose or reload its open tabs on device rotation.

* The browser **must** be accessible/launchable from the status activity.

* After the browser is launched from the status activity, the system Back button **must** bring the user back to the status activity.

* Hitting the system Back button to hide the browser **must** not destroy tabs or force reload.

* There **should** be separate system task list entries for the status activity and browser activity.

* When the server handshake succeeds, the browser **must** be brought to the foreground.

##### Nice-to-haves and future reqs

* Having dedicated launcher buttons for the status activity and the browser activity would be nice.

## Implementation

The two activity files are [`StatusActivity.java`](https://bitbucket.org/psiphon/psiphon-circumvention-system/src/tip/Android/src/com/psiphon3/StatusActivity.java) (the Psiphon status messages list) and [`MainActivity.java`](https://bitbucket.org/psiphon/psiphon-circumvention-system/src/tip/Android/zirco-browser/src/org/zirco/ui/activities/MainActivity.java) (Zirco Browser, used as the Psiphon browser).

### App Manifest

See [`AndroidManifest.xml`](https://bitbucket.org/psiphon/psiphon-circumvention-system/src/tip/Android/PsiphonAndroid/AndroidManifest.xml).

#### `launchMode`

Both the status activity and the browser activity are started with a `launchMode` value of `singleTask`. That gives us a number of desirable behaviours (paraphrased from the [official docs](http://developer.android.com/guide/topics/fundamentals/tasks-and-back-stack.html#TaskLaunchModes)):

* The system creates a new task and instantiates the activity at the root of the new task. However...

* If an instance of the activity already exists in a separate task, the system routes the intent to the existing instance through a call to its onNewIntent() method, rather than creating a new instance. So...

* Only one instance of the activity can exist at a time.

* Although the activity starts in a new task, the Back button still returns the user to the previous activity.

**Note**: For the browser activity, having `launchMode=singleTask` in the manifest and specifying no flag on the intent seems to be identical to not specifying a `launchMode` and setting an intent flag of `FLAG_ACTIVITY_NEW_TASK`. This corresponds with the documentation -- the flag overrides the `launchMode`, and both values achieve the same goal. We will leave both values in the code to ease with clarity of reading, at the risk of confusion if someone tries to remove one but not the other.

#### `taskAffinity`

The Android documentation says that `FLAG_ACTIVITY_NEW_TASK` "produces the same behaviour as the "`singleTask`" `launchMode` value", but then goes on to describe `FLAG_ACTIVITY_NEW_TASK` behaviour that wasn't mentioned for `singleTask`; the [section on task affinities](http://developer.android.com/guide/topics/fundamentals/tasks-and-back-stack.html#Affinities) says:

> if the intent passed to `startActivity()` contains the
> `FLAG_ACTIVITY_NEW_TASK` flag, the system looks for a
> different task to house the new activity. Often, it's a
> new task. However, it doesn't have to be. If there's
> already an existing task with the same affinity as the
> new activity, the activity is launched into that task.

That is consistent with the observed behaviour (before setting the `taskAffinity`): the browser activity was being launched into the same task as the status activity. (And, in fact, was killing the status activity. Which is slightly confusing, but beside the point.)

With the `taskAffinity` value set to a non-default value (we use `":PsiphonBrowserTask"`), the browser activity is reliably launched into a new task. That task shows up separately on the system task switcher, where it has a label set by the browser activity's `label` value.

#### `configChanges`

Following from the Zirco manifest settings, we have set the following on teh browser activity: 

    android:configChanges="keyboardHidden|orientation"

From the [documentation](http://developer.android.com/guide/topics/manifest/activity-element.html#config):

> When a configuration change occurs at runtime, the 
>activity is shut down and restarted by default, but 
>declaring a configuration with this attribute will 
>prevent the activity from being restarted.

Without this setting, the tab state gets reset on device rotation (reloaded if tab-restore is on, completely lost if not).

### Events

See [`Events.java`](https://bitbucket.org/psiphon/psiphon-circumvention-system/src/tip/Android/src/com/psiphon3/Events.java).

The `Events` class is the primary location for intents being created and activities being launched. Its helpers are called from both the status activity and the Psiphon service. Here are some of the actions exposed at this time:

* `appendStatusMessage`: The Psiphon service tells the status activity to append a log message. The intent is a local broadcast rather than explicit, so the status activity is not forced to the foreground.

* `signalHandshakeSuccess`: The Psiphon service tells the status activity that the server handshake has completed. This is an explicit intent and the status activity is foregrounded. It then in interprets the intent as an indication that the browser should be launched. 

* `signalUnexpectedDisconnect`: The Psiphon service tells the status activity that it has been unexpected disconnected from the server. This is an explicit intent and the status activity is foregrounded. No further action is taken.

* `displayBrowser`: The status activity uses this launch the browser activity. This is triggered either via the `signalHandshakeSuccess` handler or the "Open Browser" button. It is an explicit intent.

Note that the `FLAG_ACTIVITY_NEW_TASK` flag **must** be set on intents sent by the Psiphon service. If absent, Android will throw a runtime exception. (This is probably due to the fact that services don't have a task onto which to push the activity.)									

## Results

Target behaviour as described by the requirements is currently being achieved.

## Resources

* [Android Dev Docs: Tasks and Back Stack](http://developer.android.com/guide/topics/fundamentals/tasks-and-back-stack.html)