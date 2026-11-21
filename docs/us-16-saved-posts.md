# US-16 Save Routines — the posts part

Local work only; not pushed or deployed.

## The story (SRS, Could Have)

As a user, I want to bookmark routines **and posts** so that I can come back to them later
without copying them yet.

* **AC1** — Given the user saves a routine, when they open "Saved", then it appears in the list.
* **AC2** — Given a saved routine becomes private, when the user opens "Saved", then it is no
  longer accessible.

Routines were done earlier (`/saved-routines`, My Routines → Saved Routines). Posts could not be
bookmarked at all; this adds them, with the same two criteria applied to posts and nothing more.

## Behaviour

* **Save** sits next to Like / Comments / Report on someone else's public post (another user's
  profile → View post, and anywhere the shared posts module shows such a post). It turns into
  **Saved**; pressing it again removes the bookmark.
* posts.html and Profile → Posts have two tabs: **My posts** (as before) and **Saved posts**,
  newest bookmark first, with Like, Comments, Report and Saved on each.
* Saved shows only what the user may still see, through the same gate as viewing a post
  (`PostService.canView`): a post made private, hidden by an administrator, or whose author is
  banned or hidden drops out (AC2), and comes back if that is undone, since the bookmark is kept.
* Only public posts can be saved (someone else's private post answers 404, one's own 400). Saving
  twice keeps one bookmark; removing is safe to repeat. Bookmarks are seen only by their owner.
* Deleting a post (author or administrator) or a user removes its bookmarks with it.

## API (signed-in user only)

| Call | Does |
|---|---|
| `POST /saved-posts/{postId}` | saves; returns the post |
| `GET /saved-posts` | the user's saved posts still visible to them |
| `DELETE /saved-posts/{postId}` | removes; always 204 |

Posts in every post response now carry `savedByMe` (added at the end; no field changed).

## Database

One new table, `saved_posts`, created by Hibernate on start (`ddl-auto=update`); the same DDL is
in `db/manual/saved_posts.sql` (idempotent, not run automatically). No existing table changes;
the previous release ignores the table, so rolling back needs no database step.
