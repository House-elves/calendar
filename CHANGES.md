# What's new

What changed for you in each version of Calendar, newest first. The desk shows the parts that are new when it offers
you an update.

## 0.7.0

- Outlook at an organisation that only allows its own app, the way Evolution signs in: set **Microsoft organisation**
  and **Microsoft return address**. Sign in to Microsoft then gives you a link, and you paste the address you land on.

## 0.6.0

- New: a tidier Settings. Pick **On this machine** or **Online** to add a calendar, and the directions moved into a
  **?** you can hover over, so the long text is gone.
- New: **Online** asks where the calendar lives (Google, Microsoft, iCloud or Other), then only for what that one
  needs. For Microsoft, choose **Sign in** or paste its **ICS link**.
- New: **On this machine** has a **Find calendars** button. It lists the calendars Evolution, Thunderbird or Apple
  Calendar keep here, and now also the .ics files in your Documents, Downloads and Desktop folders. Tick the ones you
  want and click **Add calendar**: no more typing a file's path.

## 0.5.0

- New: add the calendars Evolution, GNOME Calendar, Thunderbird or Apple Calendar already show, with one click, in
  Settings under From apps on this machine. Those apps sign in to your work account themselves, so this works even
  when Outlook won't publish a calendar and your admin hasn't allowed anything else. Calendar reads the copy the app
  keeps on this machine and never changes it, so it's as up to date as the app's last sync.

## 0.4.0

- New: Sign in to Microsoft, in Settings under Your calendars, for Outlook on the web at work when there's no
  Publish a calendar and no Save Calendar. It reads your calendar the way Thunderbird and Evolution do: you type a
  code at microsoft.com/devicelogin and sign in there, and it asks only to read your calendars. It needs a Microsoft
  app ID in Settings (the README says how to make one), and your admin may have to allow that app, as they allowed
  Thunderbird.

## 0.3.0

- New: a calendar can be an .ics file on this machine instead of an address. If your Outlook has no Shared calendars
  or Publish a calendar, because your admin turned publishing off, save the calendar as an .ics file (in classic
  Outlook for Windows: File, Save Calendar, More Options, Full details) and paste its path, like
  ~/Documents/work.ics, where you'd paste an address. It's read again at each refresh, so save over the same file to
  bring it up to date.

## 0.2.3

- Clearer: Settings now says where Outlook's iCal address really is: the gear at the top right of Outlook, then
  Calendar, Shared calendars, Publish a calendar. Not the calendar's own Sharing and permissions, which only invites
  people. If there's no Publish a calendar there, your admin has turned it off.

## 0.2.2

- Clearer: in Settings, where to find each kind of calendar's iCal address (Google Calendar, Outlook, iCloud, any
  other) now starts on a line of its own, so it's easier to find yours.

## 0.2.1

- Fixed: the Name box in Settings, under Your calendars, now looks like the address box next to it.
- Clearer: Settings starts with your list of calendars. Add your first, then the next, one at a time, as many as you
  like. The three "Calendar 1, 2 and 3" address rows from the first version now sit at the bottom, marked not needed:
  they never limited you to three, and a calendar you set there is already in your list.

## 0.2.0

- New: add as many calendars as you like, of any kind, in Settings under Your calendars: give each a name, paste its
  iCal address and click Add calendar. Remove takes one off. Google Calendar, Outlook, iCloud and any other calendar
  with an iCal address all work, each in its own colour.
- The three calendars you set up before are still there, in the new list, with their names and colours.

## 0.1.1

- Fixed: its first refresh, right after you add it, no longer shows as a failed run before you've pasted a calendar's
  address. With no calendar set it just says where to paste one.

## 0.1.0

- Puts up to three Google and Outlook calendars together in one day, week or month view, each in its own colour,
  with a checkbox to hide it. A meeting on more than one calendar shows once.
- Click an event for all its details, with a Join button for its Meet, Teams, Zoom or Webex link.
- Its dashboard card says when your next meeting starts.
- Reads every hour on weekdays from 7:00 to 18:00, and only reads: it never changes a calendar.
