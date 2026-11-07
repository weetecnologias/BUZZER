import React from 'react';
import { Image, Linking, Pressable, StyleSheet, Text, View } from 'react-native';
import { API_URL } from './api';
import { colors, font, radius, space } from './theme';

export type Attachment = {
  id: string; file_name: string; content_type: string; size_bytes: number; visibility: 'PRIVATE' | 'PARTNERS'; note: string | null;
  uploader_role: string; uploaded_by_name: string | null; created_at: string; url: string;
};

const kb = (n: number) => (n > 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`);

/**
 * Request attachments: photo thumbnails and PDF rows. Links are short-lived signed URLs from the API, so they open
 * without exposing the file publicly. Pass onDelete to show "Remove" on the viewer's own uploads.
 */
export function AttachmentList({ items, onDelete, canDelete, showVisibility = true }: {
  items: Attachment[] | null | undefined; onDelete?: (a: Attachment) => void; canDelete?: (a: Attachment) => boolean; showVisibility?: boolean;
}) {
  if (!items?.length) return <Text style={{ color: colors.textSecondary, fontSize: font.sm }}>No attachments yet.</Text>;
  return (
    <View style={{ gap: space.sm }}>
      {items.map((a) => {
        const href = a.url.startsWith('http') ? a.url : API_URL + a.url;
        const image = a.content_type.startsWith('image/');
        return (
          <View key={a.id} style={styles.row}>
            <Pressable accessibilityRole="link" accessibilityLabel={`Open ${a.file_name}`} onPress={() => Linking.openURL(href)} style={styles.thumbWrap}>
              {image ? <Image source={{ uri: href }} style={styles.thumb} accessibilityIgnoresInvertColors /> : <Text style={{ fontSize: 26 }}>📄</Text>}
            </Pressable>
            <View style={{ flex: 1 }}>
              <Text style={{ fontWeight: '700', color: colors.text }} numberOfLines={1}>{a.file_name}</Text>
              <Text style={{ color: colors.textSecondary, fontSize: font.xs }}>
                {kb(a.size_bytes)}{showVisibility ? ` • ${a.visibility === 'PARTNERS' ? 'Shared with quoting partners' : 'Private'}` : ''}
                {a.uploaded_by_name ? ` • ${a.uploader_role === 'CUSTOMER' ? a.uploaded_by_name : `${a.uploader_role.toLowerCase()} ${a.uploaded_by_name}`}` : ''}
              </Text>
              {a.note ? <Text style={{ color: colors.text, fontSize: font.xs }}>{a.note}</Text> : null}
            </View>
            {onDelete && (!canDelete || canDelete(a)) ? (
              <Pressable accessibilityRole="button" accessibilityLabel={`Remove ${a.file_name}`} onPress={() => onDelete(a)} hitSlop={6}>
                <Text style={{ color: colors.danger, fontWeight: '700', fontSize: font.sm }}>Remove</Text>
              </Pressable>
            ) : null}
          </View>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  thumbWrap: { width: 56, height: 56, borderRadius: radius.md, backgroundColor: colors.lightGreen, alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  thumb: { width: 56, height: 56 },
});
