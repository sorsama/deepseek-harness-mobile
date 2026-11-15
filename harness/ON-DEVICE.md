# The harness on the phone itself (built-in): user guide

DSH Mobile can run the DeepSeek Harness inside the app. There is no computer, no Termux and no
command line: the app downloads the harness once, starts it on the phone, and connects to it. This
is the simplest way to use the app, and on a phone that supports it, a new install opens on it.

The other ways stay as they were: a harness on your computer (Local network, Relay, USB), or the
harness in Termux ([`TERMUX.md`](TERMUX.md)).

**Beta.** The built-in harness is new in 0.22.0 and the app labels it Beta. It was tested on
emulators (Android 11, 15 and a desktop image) and one Samsung phone, so it may not work on every
phone yet. If it gives you trouble, the ways above still work, and an issue with your phone's model
and Android version helps a lot.

## What it is

- **A small Linux inside the app.** A Debian base with Node.js and the harness, run with `proot`
  (shipped in the app). It is the same DeepSeek Harness you would run on a computer, listening on
  `127.0.0.1` on the phone, with the same sessions, tools and web protocol.
- **Signed and checked.** Everything comes from the releases of
  [`sorsama/dsh-mobile-runtime`](https://github.com/sorsama/dsh-mobile-runtime). The app checks a
  signature and a checksum for every file before it unpacks anything
  ([`docs/SECURITY.md`](../docs/SECURITY.md), "This phone (built-in harness)").
- **Its own updates.** New harness releases arrive without an app update. The app itself updates
  from inside the app too (Settings, About).

## What it needs

| | |
|---|---|
| Phone | Android 8.0 or newer, 64-bit (arm64, which is almost every phone from the last years; x86_64 for emulators and some Chromebooks). On other phones the option does not show. |
| Download | About 155 MB the first time. An update is usually about 85 MB (only the harness part). The app asks first on mobile data. |
| Storage | About 1.4 GB free to set up, about 1.1 GB used afterwards, plus your projects and sessions. |
| Memory | The harness uses about 300 to 400 MB of RAM while it runs. Phones with 4 GB or more are comfortable. |
| A model | An API key for a model provider (DeepSeek, or another one the harness supports). You add it in the app. |

## What it can and cannot do

**Can:** everything the harness does on a computer that does not need a computer's hardware or a
sandbox: chats and agents, reading and writing files in your projects, running shell commands
(`bash`, `git`, `node`, `npm`, `curl`, `ripgrep` are there; more can be added with
`apt-get install`, Python included), subagents, scheduled tasks, cloning Git
repositories, and the app's previews, terminal and Files tab.

**Cannot:**

- run commands in a sandbox. Android does not allow one inside an app (see **Commands** below);
- reach your photos, downloads, contacts or other apps. It only sees its own projects folder;
- use the phone's GPU or run a model locally. It talks to a model provider over the internet;
- keep running if Android decides to stop the app. See **Keeping it running**.

## Set up

1. Open the app. On **This phone**, the card says *The easiest way to start: the harness runs
   inside this app, with no computer and no Termux*, with the sizes. Tap **Set up**.
2. The set-up page shows five steps: download, check, unpack, start, sign in. You can leave the
   page or the app; it keeps going. A download cut by the network waits and picks up where it
   stopped. On mobile data the app asks first.
3. While it downloads, the page explains how commands work on a phone and asks
   **When the agent wants to run a command**: **Run it** or **Ask me first**. You can change this
   later.
4. When it is done the app connects by itself. On an emulator this takes about a minute; on a
   phone it depends on the network and the storage speed.

## Add a model

A fresh harness has no API key, so the chat says *No model provider is ready yet, so messages
can't be answered. Add an API key first.* Tap **Set up models** (also in Settings, **Models**).
Pick the provider, paste the key, tap **Save**. The key goes to the harness on the phone and is
not kept by the app. The provider then reads **Ready**.

## Your first task

Open the session list (swipe in from the left edge), start a new session, and type what you want
done. To work on real code, start a project first: in **New session in...**, choose **Start a
project**, then a new empty project, **Clone a Git repository**, **Import a folder** or **Import a
.zip**. The session opens in it.

## Commands

On a computer the harness runs commands in a sandbox. A phone has none, so the app offers two
settings for new chats:

- **Run it**: commands run straight away. Simple and fast.
- **Ask me first**: each command waits for **Allow** or **Reject**, and shows the whole command.
  A long one opens in full with **Show all**. A command over 256 KB is not asked about and does
  not run.

Either way, the harness stays inside the app: it cannot see your photos, contacts, messages or
other apps. It can use the internet, and a command could delete your projects or the harness's
settings and saved keys. "Ask me first" lets you read each command; it is not a sandbox.

Each chat keeps its setting. In a chat the two show as **Full access** (Run it) and **Ask before
commands** (Ask me first); change a chat from its permission button; change the default for new
chats on the harness page (Settings, **Harness on this phone**, **Commands**). A change of the
default takes effect after a restart, which the page offers.

## Where your files are

Projects live in the app's own storage, in a folder the harness calls `/workspaces`. To reach them
from the rest of the phone:

- **The Files app.** Your projects show as a storage location named **DSH Mobile** in the system
  Files app and in every app's file picker. Open, copy in and out, rename and delete there. It works
  with the harness stopped.
- **Projects on this phone** (from the harness page, or the chat list's menu): sizes, rename,
  delete, **Open in Files**, **Share as .zip** and **Save a copy to the phone**. Heavy folders
  (`node_modules`, caches, build output) and `.git` are left out of a zip unless you ask.

Sessions and settings live in the harness's home, which is not shown in Files.

## Updates

- **The harness.** The app looks for a new release when it opens (at most every four hours) and
  once a day in the background, if **Check for updates** (Settings, About) is on. You get one
  notification per release. Tap **Update** on the harness page; on mobile data it asks first. If the
  new release does not start, the previous one comes back by itself. **Go back** returns to the
  release before. **Install updates by themselves** is off by default; when on, it installs only on
  Wi-Fi and only while no chat is working.
- **The app.** Settings, About shows a newer DSH Mobile with **Download and install**: the app
  downloads it, checks it and hands it to Android's installer. The first time, Android asks you to
  allow installs from this app. These are two separate updates, with separate notifications.
  Installing an app update closes the app and the harness with it. If the harness was running (or
  is set to start with the app), it starts again by itself once the update is in. If Android does
  not let it start in the background, it starts the next time you open the app.

## Keeping it running

Android stops apps that work in the background. While the harness runs, the app shows a
notification and keeps the phone awake (switch **Keep the phone awake** off on the harness page if
you prefer). The harness page has a **Keep it running** checklist:

- **Notifications on.** The running notification is what keeps the app alive.
- **Battery: Unrestricted.** Android Settings, Apps, DSH Mobile, Battery.
- **Background process limit.** Android 12 and 13 stop apps that run many processes in the
  background; keep DSH Mobile open while long work runs. On Android 14 and newer, turn on **Disable
  child process restrictions** in Developer options.
- **Phone makers' own battery savers** (Xiaomi, Huawei, Samsung, OnePlus, Oppo, Vivo and others)
  can stop the app anyway. [dontkillmyapp.com](https://dontkillmyapp.com/) shows the setting for
  each brand.

## Removing it

On the harness page, **Storage**: **Remove runtime** frees the space and keeps your sessions,
settings and projects for a later set-up. **Delete everything** removes those too. Uninstalling the
app removes everything.

## Troubleshooting

The app names what went wrong; find that sentence here. **Show log** on the card or the harness
page opens the harness's log; **Share** sends it (the sign-in token is masked in it).

### "Not enough free space. This needs … and the phone has … free."

The set-up or update was stopped before anything was unpacked. Tap **Free up space** (Android's
storage page), delete something, then **Try again**. The numbers include room to unpack.

### "Couldn't reach the download server. Check your connection and try again."

The release server could not be reached. Check the connection. A download that was cut while
running shows *Waiting for the network* instead and resumes by itself.

### "The download didn't match what was signed, so none of it was kept. Try again."

A downloaded file failed its checksum or signature. Nothing was installed. Try again; if it keeps
happening on one network, try another (something on the way is changing the files).

### "This harness needs DSH Mobile … or newer."

The latest harness release needs a newer app. Tap **Update the app**: the app's own update check
runs and offers the download.

### "There's no build of the harness for this phone yet."

The phone's processor type has no runtime build. The other ways to connect still work.

### "Something else on this phone is using port …, maybe a harness in Termux."

Another program holds the port. Stop the harness in Termux if you run one, or tap **Use port …**
to start on the next port.

### "The harness stopped while it was starting." / "The harness took too long to start, so it was stopped."

Open **Show log**. A first start after a set-up or an update is slower. If it keeps failing, turn
**Fast start** off on the harness page and start again; it takes effect at the next start.

### "The harness stopped on its own."

Android or the phone's battery saver stopped it, or it crashed. Tap **Restart**. If it happens with
the screen off, work through **Keeping it running**. Your sessions are safe.

### "The harness couldn't be started on this phone."

The phone refused to run the runtime's programs. Share the log with the project
([issues](https://github.com/sorsama/deepseek-harness-mobile/issues)); include the phone model and
Android version.

### "The update didn't finish, and the harness on your phone is unchanged." / "The new version didn't start, so the previous one is back."

Nothing was lost. The release that failed is not installed by itself again. You can still try
**Update anyway** later, or wait for the next release.

### "No model provider is ready yet, so messages can't be answered. Add an API key first."

See **Add a model**. *The saved API key isn't in a form the provider accepts* means the key was
mistyped; replace it.

### "This chat is on a setting that needs a sandbox, and a phone can't give it one, so that command didn't run."

The chat was made with an older harness setting. Tap **Switch to "Full access"** (or "Ask before
commands", whichever matches your choice for new chats) and ask again.

### "A helper agent can't ask you, so this command didn't run."

In an **Ask me first** chat a subagent cannot ask, so its commands are refused. Ask the main agent
to run the command itself, or switch the chat to **Run it**.

### "This was too long to show you in full (the limit is 256 KB), so you weren't asked and it didn't run."

The agent tried one huge command. It was told to split it; nothing ran.

### "This harness version can shorten a long command when it asks you…"

The installed harness is from before the full-text questions. Tap **Update** (or **Check for
updates**) on the harness page before you rely on **Ask me first**.

### "The repository was not found. If it is private, turn on Private repository and add a token."

Check the address. For a private repository, turn on **Private repository** and paste a token that
can read it; the token is used for that clone only and is not saved. SSH addresses are not supported;
use the `https://` address.

### "This session's folder is gone. It was deleted or renamed."

The project was deleted or renamed in Files or on the Projects page. Start a new session in a
project.

### The harness dies when the screen goes off

Battery optimisation, the child process limit, or the phone maker's battery saver, in that order.
See **Keeping it running**.
