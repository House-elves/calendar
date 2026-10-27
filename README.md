<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# calendar

An elf for [Elf Desk](https://github.com/House-elves/elf-desk): **Calendar** puts all your calendars (Gmail, Google
Workspace, Office 365, iCloud, or anything else with an iCal address or `.ics` file, as many as you like) together in one **Day**,
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
  machine you add instead of an address. Nothing else.
- **Writes:** nothing outside this machine. The calendars you add are kept in `~/.config/calendar/config`, and the
  events are saved in `~/.config/calendar/events/`. It never accepts, declines, creates or changes anything on a
  calendar.

## Adding it

1. On Elf Desk, click **+** and pick **Calendar**.
2. In its **Settings**, under *Your calendars*, give each calendar a short name, paste its private iCal address and
   click **Add calendar**. Add as many as you like (up to 20); **Remove** takes one off again.
   - **Google Calendar:** Settings, your calendar, *Integrate calendar*, *Secret address in iCal format*. If a work
     account has no such field, your admin has turned it off.
   - **Outlook on the web (Office 365, Outlook.com):** not the calendar's own *Sharing and permissions*, which only
     invites people by email. Click the gear at the top right of Outlook for *Settings*, then *Calendar*, *Shared
     calendars*. Under *Publish a calendar*, pick the calendar and *Can view all details* (or meeting links are left
     out), click *Publish*, then copy the ICS link.
   - **Outlook with no *Shared calendars* or *Publish a calendar*:** your admin has turned publishing off. Ask them to
     allow it, or save the calendar as an `.ics` file and paste its path instead of an address, like
     `~/Documents/work.ics`. In Outlook for Windows (the classic one): *File*, *Save Calendar*, *More Options*, *Full
     details* and the dates you want. It reads the file again at each refresh, so save over the same file to bring it
     up to date.
   - **iCloud:** share the calendar as a *Public Calendar* and copy its link (`webcal://` is fine).
   - **Anything else** that gives you an iCal (`.ics`) address works the same way.
3. It reads them straight away; **Refresh calendars** reads them again.

Anyone who has one of these addresses can read that calendar, so treat it like a password. The desk keeps it in
`~/.config/calendar/config` and never shows it again.

## Running it by hand

    echo '{"name":"Holidays","address":"https://calendar.google.com/calendar/ical/en.uk%23holiday%40group.v.calendar.google.com/public/basic.ics"}' \
        | jbang Calendar.java --action=add      # adds a calendar to ~/.config/calendar/config
    echo '{}' | jbang Calendar.java --action=calendars
    echo '{"slot":1}' | jbang Calendar.java --action=remove
    jbang Calendar.java            # prints what it read, saves nothing
    jbang Calendar.java --save     # saved for its tab on Elf Desk

Each calendar is a pair of lines in the config, `CAL<n>_NAME` and `CAL<n>_ICS_SECRET`, for `n` from 1 to 20. The three
*Calendar 1, 2 and 3 from the first version* rows at the bottom of its Settings are those first three addresses, from
before the list: you don't need them, and one set there already shows in *Your calendars*.

Check it with `jbang https://github.com/House-elves/elf-runner/blob/main/ElfRunner.java --check-elf .`
