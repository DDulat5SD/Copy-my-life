# CopyMyLife Traceability Table

This table maps the user interface fields and actions to the corresponding database entities and attributes in the ERD.

| Screen / UI | Interface Field / Action | Database Table | Database Attribute |
|---|---|---|---|
| Sign Up | Full name | users | name |
| Sign Up | Email | users | email |
| Sign Up | Password | users | password |
| Login | Email | users | email |
| Login | Password | users | password |
| Edit Profile | Name | users | name |
| Edit Profile | Username | users | username |
| Edit Profile | Bio | users | bio |
| Edit Profile | Avatar | users | avatar_url |
| Create Routine | Title | routines | title |
| Create Routine | Description | routines | description |
| Create Routine | Category | routines | category |
| Create Routine | Duration | routines | duration_minutes |
| Create Routine | Public checkbox | routines | is_public |
| Create Routine | Image | routines | image_url |
| Create Routine | Current user | routines | user_id |
| Create Habit | Habit name | habits | title |
| Create Habit | Description | habits | description |
| Create Habit | Duration | habits | duration_minutes |
| Create Habit | Selected routine | habits | routine_id |
| Calendar | Routine | calendar_events | routine_id |
| Calendar | Day | calendar_events | start_date |
| Calendar | Time | calendar_events | start_time |
| Calendar | Repeat | calendar_events | repeat_type |
| Calendar | Current user | calendar_events | user_id |
| Routine Details | Comment | routine_comments | text |
| Routine Details | Rating | routine_ratings | value |
| Routine Details | Like | liked_routines | user_id, routine_id |
| Routine Details | Save | saved_routines | user_id, routine_id |
| Habit | Save habit | saved_habits | user_id, habit_id |
| Habit Tracker | Complete habit | habit_completions | user_id, habit_id, completed_date |
| Routine Tracker | Complete routine | routine_completions | user_id, routine_id, completed_date |
| Site Rating | Rating | site_ratings | value |
| Forgot Password | Reset token | password_reset_token | token |
| Forgot Password | Token owner | password_reset_token | user_id |
| Forgot Password | Expiration | password_reset_token | expires_at |
