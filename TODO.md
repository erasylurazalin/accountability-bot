# TODO

## Letting other people use it

When the first version worked, I thought about opening it up to other students
at my university. I decided against it for now:

- The Telegram side is rough. Commands work, but there's no onboarding: a new
  person would get no reminders until they found `/quiet`, and nothing tells
  them that.
- I've only used it myself. I don't know yet whether random reminders
  actually help or just annoy, so I can't promise anyone it's worth their time.

It's still something I want to do fairly soon. The data model is mostly ready
for it: every table is keyed by chat id, quiet hours and time zone are per
person, and the daily cap is per person, not per deadline. What's missing:

- Replace the single owner check with an allowlist, or an invite code.
- A `/start` that walks a new user through quiet hours and time zone.
- Tidy up the buttons and message wording.
- Think about privacy properly: deadline names are personal, and they sit in
  the database and in the backups.

## Backups

- An off-machine copy. Right now the backups sit on the same disk as the
  database.

## Tests

- Command parsing, DST, and deadline expiry aren't covered yet.
