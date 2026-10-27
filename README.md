<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# calendar

An elf for [Elf Desk](https://github.com/House-elves/elf-desk): **Calendar** puts up to three calendars (say Gmail, a
Google Workspace one and an Office 365 one) together in one **Day**, **Week** or **Month** view. Each calendar has its
own colour and a checkbox to hide it. A meeting that's on more than one calendar shows once, marked with each.

Click an event to see everything in it: time, place, organiser, who's invited, the description and its calendar. A
meeting has a **Join** button for its Google Meet, Teams, Zoom or Webex link, and the other links in it work too.
Its dashboard card says when your next meeting starts, like "Team sync in 25 minutes".

It reads every hour from 7:00 to 18:00 on weekdays, and when you click **Refresh calendars**, from the start of last
week to **Weeks ahead** (6 unless you change it). It saves the events on this machine, so the tab opens straight away,
even offline. If one calendar can't be read, it keeps showing what it read last time and says so.

- **Reads:** your calendars, through the private iCal addresses you give it: Google Calendar
  (calendar.google.com) and Outlook (outlook.office365.com). Nothing else.
- **Writes:** nothing outside this machine. The events are saved in `~/.config/calendar/events/`. It never accepts,
  declines, creates or changes anything on a calendar.

## Adding it

1. On Elf Desk, click **+** and pick **Calendar**.
2. In its settings, paste each calendar's private iCal address and give it a short name:
   - **Google Calendar:** Settings, your calendar, *Integrate calendar*, *Secret address in iCal format*. If a work
     account has no such field, your admin has turned it off.
   - **Outlook on the web (Office 365):** Settings, Calendar, *Shared calendars*, *Publish a calendar*, choose *Can
     view all details* (or meeting links are left out), then copy the ICS link.
3. Click **Refresh calendars**.

Anyone who has one of these addresses can read that calendar, so treat it like a password. The desk keeps it in
`~/.config/calendar/config` and never shows it again.

## Running it by hand

    mkdir -p ~/.config/calendar && echo 'CAL1_ICS_SECRET=https://calendar.google.com/calendar/ical/…/basic.ics' > ~/.config/calendar/config
    jbang Calendar.java            # prints what it read, saves nothing
    jbang Calendar.java --save     # saved for its tab on Elf Desk

Check it with `jbang https://github.com/House-elves/elf-runner/blob/main/ElfRunner.java --check-elf .`
