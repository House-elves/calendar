<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# elf-template

The elf to start a new [House Elves](https://github.com/House-elves) elf from, and the guide to writing one:
[AGENTS.md](AGENTS.md). Elf Desk's Elf Maker builds new elves from it too.

As it is, it's a working elf, **My elf**. It reads a news feed (RSS or Atom) and lists its newest items in its tab,
with what you haven't read first. **Done** marks an item read. With **Sum up with Claude** on, Claude adds a line or
two about what's new. It reads the feed on weekday mornings, and its dashboard card says how many items are new.

- **Reads:** the feed you give it, over the internet. With Sum up on, Claude reads the new items, with no tools.
- **Writes:** nothing outside this machine. What it read is saved in `~/.config/my-elf/runs/`, and what you marked
  read in `~/.config/my-elf/done/`.

## Making your own elf from it

1. Click **Use this template** on GitHub, or copy the files.
2. Rename `my-elf` and `MyElf` everywhere, then change what it does. [AGENTS.md](AGENTS.md) says how each part works.
3. Check it: `jbang https://github.com/House-elves/elf-runner/blob/main/ElfRunner.java --check-elf .`
4. Push it and tag the repo `house-elf`. It shows up on Elf Desk's **+** page.

## Running it by hand

    mkdir -p ~/.config/my-elf && echo 'FEED_URL=https://quarkus.io/feed.xml' > ~/.config/my-elf/config
    jbang MyElf.java            # prints what it read, saves nothing
    jbang MyElf.java --save     # saved for its tab on Elf Desk
