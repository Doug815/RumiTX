# Supabase migration boundary

Supabase is not configured in the deployed site. Do not mistake this document for a provisioned project.

To migrate, create a free Supabase project under the owner's account, configure Google OAuth and email auth, and adapt `lib/server.ts` and API identity. Keep service-role credentials server-only. Mirror current record ownership using auth.uid(), with RLS enabled on every private table. Never let members write their own admin role.

Recommended normalized tables: profiles(id references auth.users, display_name, bio, avatar_url, joined_at), roles(user_id, role), posts(id, author_id, category, title, body, pinned, locked, solution_reply_id, created_at), replies(id, post_id, author_id, body, created_at), votes(post_id,user_id unique), lab_states(user_id primary key,state jsonb), ai_logs(id,user_id,input,context,output,latency_ms,mode,created_at), newsletter(user_id,email,consented_at), apk_builds(id,version_code,version_name,sha256,bytes,storage_key,channel,approved_by).

Repeaters: id, callsign, tx_mhz, rx_mhz, offset_mhz, tone, city, state, latitude, longitude, mode, power_w, antenna_height_m, operational_status, synthetic boolean, source, verified_at. Index state/city, mode/rx_mhz, and geography (PostGIS GIST) for nearest queries. Import the provided real dataset only after checking provenance and distinguishing stale/unverified records.

Policies: public read approved releases and public repeaters; members insert their own posts/replies/votes; author/admin edit appropriate records; admin-only moderation and APK metadata/promotion; state/logs private to owner; admin-only hardware mutation; no public newsletter reads. Uploaded APKs belong in a private bucket until approved. Recheck RLS with anonymous/member/admin integration tests before replacing Sites identity.

Client retrieval: AbortController at 30 seconds; surface a pending status at 15 seconds. Measure p50/p95 end-to-end latency and cancellation on the deployed database, not just function computation.
