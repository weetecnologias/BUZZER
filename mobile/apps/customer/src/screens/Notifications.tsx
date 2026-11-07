import React from 'react';
import { NotificationsInbox, useNav } from '@buzzer/core';

export function NotificationsScreen() {
  const nav = useNav();
  return <NotificationsInbox onOpen={(n) => { if (n.ref_type === 'REQUEST' && n.ref_id) nav.push('RequestDetail', { id: n.ref_id }); }} />;
}
