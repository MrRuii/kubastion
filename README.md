# kubastion

**A browser terminal that logs into your cluster the way you already do — then turns
`kubectl get pods` into a live table.**

If you can reach your cluster's API from your laptop, **stop reading and use
[k9s](https://k9scli.io) or [Headlamp](https://headlamp.dev)** — they are excellent and
this project is not trying to replace them.

kubastion exists for the other situation:

- the cluster API is **not reachable** from your machine
- you get there through a **bastion / jump host**, often with an **interactive menu**
- you **cannot install anything** on that remote machine
- your SSH credential is **short-lived** (it expires every couple of hours)
- so you end up retyping the same `kubectl` commands all day long

**The key idea: kubastion does not automate your login.** You do it yourself, by hand,
inside a real terminal in the browser — key, `ssh`, gateway menu, destination, MFA,
whatever your environment throws at you. Once you are on the machine, you press
*start monitoring* and it takes over the boring part.

That is why it works with gateways it knows nothing about.

Search terms, so people with this problem can find it: *kubectl through bastion*,
*kubernetes UI without API access*, *k9s jump host*, *kubectl over ssh dashboard*,
*kubernetes dashboard behind jump server menu*.

---

## Security: it never touches your credentials

This is the first thing your security team will ask, so it is the first thing documented.

- kubastion **never asks for, stores, reads or transmits your key, password or OTP.**
  It opens a local shell and gets out of the way; you type into it exactly as you would
  into any terminal.
- It **does not extend the lifetime of any credential** and does not try to work around
  expiry.
- It runs on `127.0.0.1` only and talks to nothing else. No telemetry, no network calls.
- The only thing it injects into your session is a `kubectl get pods` command, on a timer,
  and only after you explicitly press a button.

## What it does (v0.1)

1. **A real terminal in the browser** (xterm.js over a PTY), so interactive gateway menus,
   prompts and colours behave exactly as in your normal terminal.
2. **You log in yourself** — kubastion neither sees nor automates any of it.
3. **Start monitoring** → it runs `kubectl get pods -o json` every 3 seconds in that same
   session and renders a live table: status, ready, restarts, age, node.

The polled command and its output are **hidden from the terminal**, so your session stays
readable instead of being flooded with JSON every three seconds.

## Requirements

- Java 21 and Node 20+ to build
- A machine you reach through a terminal, with `kubectl` already configured on it

Nothing needs to be installed on the remote side.

## Quick start

```bash
cp config.example.yml config.yml     # then edit it — config.yml is git-ignored
cd backend  && mvn spring-boot:run
cd frontend && npm install && npm start
```

Open <http://localhost:4200>, log in through the terminal as usual, then press
*start monitoring*.

## How it works

```
browser                    kubastion (localhost)            your gateway        cluster
┌──────────┐  websocket   ┌───────────────────┐   PTY      ┌──────────┐        ┌───────┐
│ xterm.js │ ◄──────────► │  terminal session │ ◄────────► │  ssh …   │ ─────► │  API  │
└──────────┘              │                   │            │  menu    │        └───────┘
┌──────────┐  websocket   │  every 3s, hidden:│            │  kubectl │
│ pod table│ ◄─────────── │  kubectl get pods │            └──────────┘
└──────────┘              └───────────────────┘
```

The injected command is wrapped in random markers so its output can be separated from
your own and suppressed from the view:

```
echo "__KB<id>""S__"; kubectl get pods -o json 2>&1; echo "__KB<id>""E__"
```

The markers are split across two adjacent strings on purpose: a PTY echoes the command
line back, and if the literal markers appeared in it, the capture would close on the echo
instead of the real output. Split like this, only the *output* of `echo` contains the
marker.

> **The injected command assumes a POSIX shell** (`;`, `2>&1`, string concatenation).
> That is the point: by the time you press *start monitoring* you are on the remote Linux
> machine. Keep `terminal.command` empty so the local shell stays your OS default.

## Non-goals

Keeping this list honest is how the project stays small enough to be maintained:

- Not a k9s or Headlamp replacement — if you can reach the API, use those.
- No credential handling, storage or automation of any kind.
- No cluster mutation (no apply, no delete).
- No multi-cluster, no plugins, no themes.

## Roadmap

Next, in order: logs on demand per pod, pause monitoring while you type, other resource
types. Deliberately not started until the above is used in anger.

## Status

Early, but verified end to end: PTY, interactive shell, command injection, output capture,
JSON parsing and the live table all work. What has *not* been exercised yet is a real
corporate gateway — that is the next thing to find out.

## License

MIT
