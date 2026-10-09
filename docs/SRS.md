# Software Requirements Specification — CopyMyLife

**Course:** SOE 205 Capstone Project  
**Team size:** 4 (UI/UX Designer, Frontend Developer 1, Frontend Developer 2, Backend Developer)

---

## 1. Introduction

### 1.1 Purpose
This document describes the functional and non-functional requirements for **CopyMyLife**, a social web platform for sharing and copying productive daily routines. It is intended for the development team, the course instructor, and anyone reviewing the project scope.

### 1.2 Product Overview
CopyMyLife is an Instagram-like platform focused on productivity. Users publish their lives — daily routines, to-do lists, photos, and videos — to show how they organize their time. Other users follow them, watch their routines, and copy either a full routine or single habits into their own schedule, adapt it to their life, and track how consistently they follow it.

### 1.3 Problem Statement
Today, almost everyone's life is visible online, and people often look at successful or productive people for inspiration. However, social media shows only the results, not the actual schedule behind them. There is no simple way to see *how* someone structures their day and to turn that into a personal plan. CopyMyLife solves this by making routines visible, copyable, and trackable.

### 1.4 Scope
**In scope:** accounts and profiles, publishing routines and media posts, following creators, a personal feed, exploring and searching routines, copying and adapting routines/habits, to-do lists, habit tracking with progress statistics, privacy settings, and admin moderation.

**Out of scope (this version):** selling or paid content, advertising, direct messaging, live streaming, native mobile apps.

### 1.5 Definitions
| Term | Meaning |
|------|---------|
| Routine | A set of timed activities for a day or week (e.g. "06:00 — Wake up, 06:15 — Run 5 km") |
| Habit / Activity | A single item inside a routine with time, duration, and repeat days |
| Post | A photo or video with a caption published by a user |
| Creator | A user who publishes public routines and posts that others follow |
| Copy | Creating a personal, editable duplicate of someone else's public routine or habit |
| Streak | Number of consecutive days a habit was completed |

---

## 2. User Roles

| Role | Description | Main permissions |
|------|-------------|------------------|
| **Visitor** | A person who opened the site without an account | Browse Explore, view public routines and profiles, search, register |
| **User (Follower)** | A registered user who follows others and tries to repeat their rhythm of life | Everything a Visitor can do + follow creators, copy routines/habits, create own routines and to-do lists, track habits, like, comment, save |
| **Creator (Influencer)** | A registered user who publishes their life publicly and is followed by others. Any User becomes a Creator by publishing public content | Everything a User can do + publish public routines, photo/video posts, see follower statistics |
| **Administrator** | A member of the platform team (developers/moderators) | Manage public content, categories, and reported posts; hide or remove inappropriate content; manage user accounts |

---

## 3. Functional Requirements

User stories are grouped into epics. Acceptance criteria use the **Given / When / Then** format.

### Epic 1: Account & Profile

**US-01. Create Account**  
As a visitor, I want to create a CopyMyLife account so that I can save routines, build my own schedule, and track habits.
- Given the email is not registered, when the visitor submits valid data, then the account is created and the user enters the authenticated area.
- Given an account with this email already exists, when the visitor submits, then no account is created and a clear duplicate-email message is shown.
- Given required fields are missing or invalid, when the visitor submits, then the form is not accepted and validation shows what to correct.

**US-02. Sign In and Sign Out**  
As a registered user, I want to sign in securely and sign out when I finish so that my personal routine data remains protected.
- Given valid credentials, when the user signs in, then protected pages become available.
- Given incorrect credentials, when the user signs in, then login is rejected and no sensitive account details are revealed.
- Given the user is authenticated, when they select Sign out, then the session ends and protected pages require login again.

**US-03. Personal Profile**  
As a user, I want to create and edit my profile with a name, photo, interests, goals, and short bio so that my account represents me.
- Given the user edits valid fields, when they save, then the profile is updated and the values remain after refresh.
- Given the user is editing, when they select Cancel, then the original data remains unchanged.

- **US-27. Weekly Routine Calendar**
- As a user, I want to see my daily routines in a weekly calendar so that I can easily organize my schedule and keep track of my planned activities.
- Given the user has added routines, when they open the weekly calendar, then the system displays their routines on the corresponding days.
- Given the user selects a day, when they click on a routine, then they can view its details.
- Given the user adds, edits, or deletes a routine, when they open the calendar, then the changes are reflected automatically.

### Epic 2: Discovery

**US-04. Explore Public Routines**  
As a visitor or user, I want to browse public routines from different people and categories so that I can discover useful lifestyle ideas.
- Given at least one public routine exists, when the visitor opens Explore, then public routines are displayed and private routines are not shown.
- Given no public routines exist, when the visitor opens Explore, then a clear empty state is shown and the page does not fail.

**US-05. Search and Filter**  
As a visitor or user, I want to search and filter profiles and routines by name, profession, category, goal, or habit type so that I can find relevant routines quickly.
- Given matching public content exists, when the user enters a keyword, then matching results are returned and the search can be cleared.
- Given nothing matches, when the user searches, then a no-results message is shown and filters can be reset.

**US-06. Routine Details and Timeline**  
As a visitor or user, I want to view a routine as a clear day or week timeline so that I can understand what a person does, when, and how often.
- Given a routine has timed activities, when the user opens it, then activities appear in chronological order with duration and repeat pattern visible.
- Given a routine has no activities, when the user opens it, then a clear empty state is shown.
-
- **US-26. AI-Powered Lifestyle Matching**
- As a user, I want AI to help me find people whose lifestyles are most similar to mine, so that I can discover users I might not be able to find through regular search.
- Given the user has a profile and daily routine, when they use AI matching, then the system recommends people with similar lifestyles.
- Given the AI finds matching users, when the recommendations are displayed, then the user can view their profiles and daily routines.
- Given the user updates their interests or routine, when they use AI matching again, then the recommendations are updated accordingly.

### Epic 3: Content Sharing (Creator)

**US-08. Create Personal Routine**  
As a user, I want to create my own routine by adding activities with time, duration, repeat days, and notes so that I can organize my daily and weekly life.
- Given the user enters valid activity data, when they save, then the routine is created and remains after refresh.
- Given required fields are missing, when the user saves, then creation is blocked and validation explains what is missing.

**US-09. Manage Habits and Activities**  
As a user, I want to add, edit, reorder, pause, and delete activities so that my routine stays accurate when my schedule changes.
- Given the user owns an activity, when they edit and save, then changes are stored and remain after refresh.
- Given the user owns an activity, when they confirm deletion, then it is removed and no longer appears.
**US-12. Creator Statistics**  
As a creator, I want to see how many followers I have and how many times my routines were copied so that I understand how useful my content is to others.
- Given the creator has public routines, when they open their statistics, then follower count and copy count per routine are shown.
- Given no one has followed or copied yet, when they open statistics, then zero values are shown without errors.

### Epic 4: Social Interaction

**US-13. Follow a Creator**  
As a user, I want to follow and unfollow creators so that I can keep up with the people whose lifestyle I want to repeat.
- Given the user views a creator's profile, when they select Follow, then the creator is added to their following list and the follower count increases by one.
- Given the user already follows the creator, when they select Unfollow, then the creator is removed and the count decreases.
**US-15. Like and Comment**  
As a user, I want to like and comment on routines so that I can support creators and ask questions about their schedule.
- Given the user is authenticated, when they like a post, then the like count increases and liking again removes the like.
- Given the user writes a non-empty comment, when they submit, then it appears under the post with their name.
- Given the user is a visitor, when they try to like or comment, then they are asked to sign in.

**US-16. Save Routines**  
As a user, I want to bookmark routines and posts so that I can come back to them later without copying them yet.
- Given the user saves a routine, when they open "Saved", then it appears in the list.
- Given a saved routine becomes private, when the user opens "Saved", then it is no longer accessible.

### Epic 5: Copying Routines

**US-17. Copy Full Routine**  
As a user, I want to save another person's complete public routine into my routine's favourites so that I can use it as a template instead of starting from zero.
- Given the user views a public routine, when they select Copy Routine, then a personal copy is created and the original remains unchanged.
- Given the routine is private, when the user attempts to copy it, then the copy is blocked and private content is not exposed.

**US-18. Copy a Single Habit**  
As a user, I want to copy one habit from another public routine so that I can improve my schedule without copying the whole routine.
- Given the user views a public habit, when they select Copy Habit, then it is added to their chosen routine and the rest of the source routine is not copied.
- Given the copy action started, when the user cancels, then nothing is added.

**US-19. Adapt a Copied Routine**  
As a user, I want to change the time, duration, frequency, and repeat days of copied activities before saving so that the routine fits my real schedule.
- Given valid changes, when the user saves, then the adjusted values are saved in the personal copy and the source stays unchanged.
- Given invalid values, when the user saves, then saving is blocked and validation identifies the problem.

### Epic 6: Tracking & Progress
**US-07. Progress Status**
As a user, I want to see my status based on completed tasks so that I can track my progress and stay motivated.
- Given the user has completed a certain number of tasks, when they view their profile, then their current status is displayed.
- Given the user reaches the requirements for the next status, when the system updates their progress, then their status is automatically upgraded.
- Given the user has not completed enough tasks, when they check their status, then they remain at their current level and can see the requirements for the next status.

**US-20. Daily Habit Tracking**  
As a user, I want to mark planned activities as completed, skipped, or postponed so that I can see how consistently I follow my routine.
- Given a planned activity for today, when the user marks it Completed, then the status is stored and today's tracker updates.
- Given a planned activity, when the user selects Skipped or Postponed, then the status is stored and remains after refresh.

**US-21. Progress and Streaks**  
As a user, I want to see completion percentage, active streaks, and weekly progress so that I understand how consistent I am.
- Given tracking records exist, when the user opens Progress, then percentage and streaks reflect stored records.
- Given no tracking records exist, when the user opens Progress, then a zero/empty state is shown without incorrect statistics.

**US-22. Routine Reminders**  
As a user, I want to receive reminders for upcoming activities so that I am less likely to forget my routine.
- Given reminders are enabled, when the activity time approaches, then a reminder about that activity is shown.
- Given reminders are disabled, when the activity time comes, then no reminder is sent.

### Epic 7: Privacy & Moderation

**US-23. Privacy Settings**  
As a user, I want to make my routines, to-do lists, and posts public or private so that I control who can see and copy them.
- Given the user owns a public routine, when they set it to Private, then other users can no longer view or copy it, but the owner still can.
- Given the user does not own the routine, when they try to change visibility, then the action is blocked.

**US-24. Report Content**  
As a user, I want to report a post, comment, or routine that is inappropriate so that the platform stays safe.
- Given the user selects Report and chooses a reason, when they submit, then the report is sent to administrators and a confirmation is shown.
- Given the user already reported this item, when they try again, then a message says it was already reported.

**US-25. Admin Content Management**  
As an administrator, I want to create, edit, hide, and remove public profiles, categories, posts, and routine data, and review reports, so that content remains organized and appropriate.
- Given an authenticated administrator, when they edit or hide content, then changes are stored and the public view reflects them.
- Given a non-admin user, when they attempt an admin action, then permission is denied and content remains unchanged.

---

## 4. Non-Functional Requirements

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-01 | Performance | Main, Explore, and Profile pages load in under **3 seconds** on a normal home internet connection. |
| NFR-02 | Performance | Search results appear in under **2 seconds**. |
| NFR-03 | Performance | Users can upload photos up to **5 MB** and videos up to **50 MB**; an upload finishes in under **10 seconds**. |
| NFR-04 | Security | Passwords are stored in hashed form, never as plain text. |
| NFR-05 | Security | Only signed-in users can create, edit, or copy routines, and private content is visible only to its owner. |
| NFR-06 | Usability | A new user can register and create their first routine in under **10 minutes** without help. |
| NFR-07 | Usability | The site is usable on both phones and laptops (screen width from **360 px**). |
| NFR-08 | Compatibility | The site works correctly in the latest versions of **Chrome** and **Firefox**. |

---

## 5. Constraints

| Area | Constraint |
|------|-----------|
| Team | 4 members: UI/UX Designer, Frontend Developer 1, Frontend Developer 2, Backend Developer |
| UI/UX design | Figma (design completed) |
| Frontend | HTML, CSS |
| Backend | Java, Spring Boot |
| Database | PostgreSQL |
| Media storage | Railway persistent volume |
| Hosting | Railway |
| Timeline | 6 iterations, 27.09.2026 – 06.12.2026 (two-week iterations) |
| Business | The platform is free; no selling, paid subscriptions, or ads in this version |
| Platform | Web only (desktop and mobile browsers); no native apps |

### Current status
- **Completed for Milestone 1:** SRS, ERD, traceability table, GitHub setup, and initial UI/UX prototype.
- **Implementation status:** backend development has started and several core features are already implemented.
- **Next phases:** continue implementation, testing, integration, and preparation for later milestones.

---

## 6. Prioritization (MoSCoW)

| Story | Title | Epic | Estimate (/10) | Priority |
|-------|-------|------|:--------------:|----------|
| US-01 | Create Account | Account & Profile | 4 | **Must** |
| US-02 | Sign In and Sign Out | Account & Profile | 4 | **Must** |
| US-03 | Personal Profile | Account & Profile | 4 | **Must** |
| US-04 | Explore Public Routines | Discovery | 4 | **Must** |
| US-06 | Routine Details and Timeline | Discovery | 5 | **Must** |
| US-08 | Create Personal Routine | Content Sharing | 6 | **Must** |
| US-09 | Manage Habits and Activities | Content Sharing | 5 | **Must** |
| US-13 | Follow a Creator | Social Interaction | 4 | **Must** |
| US-17 | Copy Full Routine | Copying Routines | 7 | **Must** |
| US-20 | Daily Habit Tracking | Tracking & Progress | 6 | **Must** |
| US-23 | Privacy Settings | Privacy & Moderation | 5 | **Must** |
| US-27 | Weekly Routine Calendar | Account & Profile | 6 | **Must** |
| US-05 | Search and Filter | Discovery | 5 | Should |
| US-18 | Copy a Single Habit | Copying Routines | 5 | Should |
| US-19 | Adapt a Copied Routine | Copying Routines | 7 | Should |
| US-21 | Progress and Streaks | Tracking & Progress | 6 | Should |
| US-25 | Admin Content Management | Privacy & Moderation | 6 | Should |
| US-26 | AI-Powered Lifestyle Matching | Discovery | 5 | Should |
| US-15 | Like and Comment | Social Interaction | 5 | Could |
| US-16 | Save Routines | Social Interaction | 3 | Could |
| US-12 | Creator Statistics | Content Sharing | 4 | Could |
| US-22 | Routine Reminders | Tracking & Progress | 6 | Could |
| US-24 | Report Content | Privacy & Moderation | 3 | Could |
| US-07 | Progress Status | Tracking & Progress | 5 | Could |


**Won't have (this version):** direct messaging, live streaming, paid content or subscriptions, advertising, native iOS/Android apps.

**Summary:** 12 Must · 6 Should · 6 Could · 5 Won't features.
