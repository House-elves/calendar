<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# calendar

An elf for [Elf Desk](https://github.com/House-elves/elf-desk): **Calendar** puts all your calendars (Gmail, Google
Workspace, Office 365 by address or by signing in to Microsoft, iCloud, the calendars Evolution, Thunderbird or Apple
Calendar already keep on this machine, or anything else with an iCal address or `.ics` file, as many as you like) together in one **Day**,
**Week** or **Month** view. Each calendar has its
own colour and a checkbox to hide it. A meeting that's on more than one calendar shows once, marked with each.

Click an event to see everything in it: time, place, organiser, who's invited, the description and its calendar. A
meeting has a **Join** button for its Google Meet, Teams, Zoom or Webex link, and the other links in it work too.
Its dashboard card says when your next meeting starts, like "Team sync in 25 minutes".

It reads every hour from 7:00 to 18:00 on weekdays, and when you click **Refresh calendars**, from the start of last
week to **Weeks ahead** (6 unless you change it). It saves the events on this machine, so the tab opens straight away,
even offline. If one calendar can't be read, it keeps showing what it read last time and says so.

- **Reads:** your calendars, through the private iCal addresses you add, from the server each one names: Google
  Calendar (calendar.google.com), Outlook (outlook.office365.com), iCloud, or any other. And any `.ics` file on this
  machine you add instead of an address. The copies Evolution (and GNOME Calendar), Thunderbird or Apple Calendar keep
  on this machine, for the calendars you pick: it copies their database to a folder of its own and reads that, so it
  never changes or locks their files. And, if you sign in to Microsoft, your Outlook calendar through Microsoft
  Graph (login.microsoftonline.com, graph.microsoft.com), with permission only to read calendars. Nothing else.
- **Writes:** nothing outside this machine. The calendars you add are kept in `~/.config/calendar/config`, and the
  events are saved in `~/.config/calendar/events/`. A Microsoft sign-in is kept in `~/.config/calendar/microsoft.json`,
  readable only by you, and goes when you remove its calendar. It never accepts, declines, creates or changes anything on a
  calendar.

## Adding it

1. On Elf Desk, click **+** and pick **Calendar**.
2. In its **Settings**, click **Add calendar**. It asks where the calendar is, then only what that one needs. Add as
   many as you like (up to 20), one at a time; **Remove** takes one off again.
   - **On this machine:** it lists the calendars Evolution, Thunderbird or Apple Calendar already keep here (see
     below), and the `.ics` files in your *Documents*, *Downloads* and *Desktop* folders (a few folders deep). Tick
     the ones you want and click **Add calendar**.
   - **Online:** give it a short name and what that one needs:
     - **Google** (Gmail, Google Workspace): its *Secret address in iCal format*: Google Calendar's Settings, your
       calendar, *Integrate calendar*. If a work account has no such field, your admin has turned it off.
     - **Microsoft** (Office 365, Outlook.com): **Sign in** (see *Signing in to Microsoft* below), or a **Published link**:
       not the calendar's own *Sharing and permissions*, which only invites people by email. Click the gear at the top
       right of Outlook for *Settings*, then *Calendar*, *Shared calendars*. Under *Publish a calendar*, pick the
       calendar and *Can view all details* (or meeting links are left out), click *Publish*, then copy the ICS link.
       No *Publish a calendar*? Your admin turned publishing off: sign in instead, or add it from Evolution or
       Thunderbird with *On this machine*. In classic Outlook for Windows you can also save the calendar as an `.ics`
       file (*File*, *Save Calendar*, *More Options*, *Full details*) in *Documents* and find it under *On this
       machine*; it's read again at each refresh.
     - **iCloud:** share the calendar as a *Public Calendar* and copy its link (`webcal://` is fine).
     - **Other:** anything that gives you an iCal (`.ics`) address.
3. It reads them straight away; **Refresh calendars** reads them again.

### From apps on this machine

Evolution, Thunderbird and Apple Calendar sign in to your accounts themselves, with your organisation's blessing, and
keep a copy of each calendar on this machine. Calendar can read that copy, so a work Outlook calendar that Evolution
shows needs no address and no admin. In Settings, click **Add calendar**, then **On this machine**, tick it and
click **Add calendar**. It's read again at each refresh, and it's as up to date as the app's last sync, so leave the app running (or open
it now and then).

- **Evolution** and **GNOME Calendar** (which uses Evolution's accounts), also from Flathub: every calendar it has, from
  Microsoft 365, Exchange, Google, CalDAV or kept only on this machine.
- **Thunderbird**, also from Flathub or Snap: its calendars, from each profile.
- **Apple Calendar**, on a Mac: the calendars it keeps as files in `~/Library/Calendars`. macOS asks before letting a
  program read that folder: give the program that runs Elf Desk *Full Disk Access* in *System Settings*, *Privacy &
  Security*. Newer versions of macOS keep their calendars in a database instead, which this doesn't read yet.

If it finds none, none of these apps has a calendar here and there's no `.ics` file in those folders.

### Signing in to Microsoft

Thunderbird and Evolution don't need a published address: you sign in to your Microsoft account, and they read the
calendar through Microsoft's own interface. **Add calendar**, **Microsoft**, **Sign in** does the same. Give the calendar a name and click **Sign in to
Microsoft**; Settings shows a code. Open the link it shows (microsoft.com/devicelogin), type the code and sign in as you always
do. Your password goes only to Microsoft. Calendar asks only to read your calendars and who you are, so it can't
change or send anything, and it stays signed in until you remove the calendar or your organisation takes it back.

Microsoft only lets an app it knows sign in, so the first time it asks for an **app ID**, and keeps it for the next
calendar. To make one,
in the [Microsoft Entra admin center](https://entra.microsoft.com): *App registrations*, *New registration*, any name,
*Accounts in any organizational directory and personal Microsoft accounts*, no redirect address. Copy its
*Application (client) ID* when Calendar asks for it. Under *Authentication*, turn on *Allow public client flows*; under
*API permissions*, add Microsoft Graph's delegated `Calendars.Read` and `User.Read`.

Your organisation may already have an app it allows for your calendar, for example one it registered for Evolution, that won't take a sign-in code. Microsoft says "Need admin approval" for any other app. Calendar can sign in with that app the way Evolution does:

- If Evolution on this machine signs in with it, Calendar finds it and offers **Use Evolution's app**.
- Otherwise, give its ID, tick *It's my organisation's own app*, and give its **Organisation** (its Directory (tenant) ID) and **Return address** (its redirect URI). Ask IT for them, or copy them from Evolution on your work computer: *Edit*, *Accounts*, your work account, *Edit*, then under *Receiving Email*, *Override Microsoft 365 (or Office365) OAuth2 settings*: *Tenant*, *Application ID* and *Redirect URI*. An empty *Redirect URI* there means `https://login.microsoftonline.com/common/oauth2/nativeclient`. They're also in Evolution's account file in `~/.config/evolution/sources/`, as `Oauth2Tenant`, `Oauth2ClientId` and `Oauth2RedirectUri`.

**Sign in to Microsoft** then gives a link instead of a code. Sign in there, and you land on a blank page. Copy its whole address and paste it into Calendar straight away. **Change app** sets another app. Such an app is allowed to change calendars (`Calendars.ReadWrite`), so that's the permission Microsoft gives Calendar, but Calendar still only reads them. Using an app your organisation registered for another program is between you and your organisation, so check that it's allowed.

Thunderbird and Evolution work at your organisation because your admin has allowed them. If signing in says your
organisation has to allow Calendar's app first, send your admin its app ID and ask them to grant it `Calendars.Read`:
it's the same kind of permission they gave Thunderbird, and less of it.

Anyone who has one of these addresses can read that calendar, so treat it like a password. The desk keeps it in
`~/.config/calendar/config` and never shows it again.

## Running it by hand

    echo '{"name":"Holidays","address":"https://calendar.google.com/calendar/ical/en.uk%23holiday%40group.v.calendar.google.com/public/basic.ics"}' \
        | jbang Calendar.java --action=add      # adds a calendar to ~/.config/calendar/config
    echo '{}' | jbang Calendar.java --action=calendars
    echo '{"name":"Work"}' | jbang Calendar.java --action=microsoft-sign-in   # shows a code to type at microsoft.com/devicelogin
    echo '{}' | jbang Calendar.java --action=microsoft-check                   # once you have: adds the calendar
    echo '{}' | jbang Calendar.java --action=local       # what Evolution, Thunderbird or Apple Calendar keep here, and .ics files
    echo '{"name":"Work","source":"evolution:<its source>"}' | jbang Calendar.java --action=add-local
    echo '{"slot":1}' | jbang Calendar.java --action=remove
    jbang Calendar.java            # prints what it read, saves nothing
    jbang Calendar.java --save     # saved for its tab on Elf Desk

Each calendar is a pair of lines in the config, `CAL<n>_NAME` and `CAL<n>_ICS_SECRET`, for `n` from 1 to 20. A
calendar you signed in to Microsoft for has `microsoft:` and your account there instead of an address, and one from an
app on this machine has `evolution:`, `thunderbird:` or `apple:` and where that app keeps it.
The Microsoft app is `MICROSOFT_APP_ID`, `MICROSOFT_TENANT` and `MICROSOFT_REDIRECT`, set by *Add calendar*.

Check it with `jbang https://github.com/House-elves/elf-runner/blob/main/ElfRunner.java --check-elf .`
