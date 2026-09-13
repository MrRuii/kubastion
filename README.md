# kubastion

**Kubernetes pods and logs from a UI, when all you have is SSH to a jump host.**

If you can reach your cluster's API from your laptop, **stop reading and use
[k9s](https://k9scli.io) or [Headlamp](https://headlamp.dev)** — they are excellent and
this project is not trying to replace them.

kubastion exists for the other situation:

- the cluster API is **not reachable** from your machine
- you get there through a **bastion / jump host** over SSH
- you **cannot install anything** on that remote machine
- your SSH credential is **short-lived** (a cert or key that expires every few hours)
- so you end up retyping `kubectl get pods` and `kubectl logs` all day long

That is the whole problem this solves. It runs entirely **on your machine**, drives the
`kubectl` that already lives on the jump host through your existing `ssh`, and gives you a
live pod list plus one-click logs.

Search terms, so people with this problem can actually find it: *kubectl through bastion*,
*kubernetes UI without API access*, *k9s jump host*, *kubectl over ssh dashboard*.

---

## Security: it never touches your private key

This is the first thing your security team will ask, so it is the first thing documented.

- kubastion **never asks for, stores, reads or transmits your private key.**
- It shells out to your **system `ssh`**, which uses your `~/.ssh/config` and your
  `ssh-agent` exactly as it does when you type commands yourself.
- It **does not extend the lifetime of any credential.** When your key expires, kubastion
  simply notices the stream died and retries; it starts working again when *you*
  re-authenticate.
- It runs on `localhost` and talks to nothing else.

If your credential is short-lived because someone decided it should be, kubastion does not
work around that decision — it just makes the expiry less annoying.

## What it does (v0.1)

- **Live pod list.** Uses `kubectl get pods --watch`, so changes appear the instant they
  happen — not on a polling interval.
- **Logs on demand.** One button per pod.
- **Survives credential expiry.** When the SSH stream dies, it reconnects automatically
  once your credential is valid again.

That is deliberately all. See [Non-goals](#non-goals).

## Requirements

- An `ssh` client on your machine (built into Windows 10+, macOS and Linux)
- SSH access to a host where `kubectl` is installed and already configured
- Java 21 and Node 20+ to build

## Quick start

```bash
cp config.example.yml config.yml     # then edit it — config.yml is git-ignored
cd backend  && mvn spring-boot:run
cd frontend && npm install && npm start
```

Open <http://localhost:4200>.

Load your credential into the agent as usual — for example, after copying it from your
internal portal:

```bash
# macOS
pbpaste | ssh-add -t 2h -
# Linux
xclip -o | ssh-add -t 2h -
```

## How it works

```
your machine                              jump host              cluster
┌──────────────┐   ssh (system binary)   ┌──────────┐  kubectl  ┌─────────┐
│  kubastion   │ ──────────────────────► │  kubectl │ ────────► │   API   │
│  UI + server │ ◄── JSON watch stream ──│          │ ◄──────── │         │
└──────────────┘                         └──────────┘           └─────────┘
```

One long-lived `ssh` process runs
`kubectl get pods -o json --watch --output-watch-events` and streams JSON events back.
No polling, no new SSH handshake per refresh, nothing installed remotely.

> On Linux and macOS, enabling SSH connection multiplexing (`ControlMaster` /
> `ControlPersist` in `~/.ssh/config`) makes the on-demand log fetches instant too.
> Windows OpenSSH does not support multiplexing, so there log requests open their own
> short-lived connection.

## Non-goals

Keeping this list honest is how the project stays small enough to be maintained:

- Not a k9s or Headlamp replacement — if you can reach the API, use those.
- No cluster mutation beyond reading (no apply, no delete) for now.
- No credential management of any kind.
- No multi-cluster, no plugins, no themes.

## Status

Early. Built to solve one person's daily annoyance; published in case it is also yours.
Issues welcome, especially from anyone in a locked-down environment — that is the whole
point of this repo existing.

## License

MIT
