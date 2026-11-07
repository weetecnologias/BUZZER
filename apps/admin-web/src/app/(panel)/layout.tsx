"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ReactNode, useEffect, useState } from "react";
import { api } from "@/lib/api";

const NAV: { section: string; items: [string, string, string][] }[] = [
  { section: "Overview", items: [["/", "📊", "Dashboard"], ["/exceptions", "🚨", "Exceptions"], ["/health", "🩺", "System health"]] },
  { section: "Operations", items: [["/requests", "📋", "Requests"], ["/customers", "👥", "Customers"], ["/agents", "🎧", "Agents"]] },
  { section: "Partners", items: [["/partners", "🧰", "Partners & KYC"], ["/price-lists", "🏷️", "Price lists"], ["/coverage", "🗺️", "Coverage"]] },
  { section: "Configuration", items: [["/catalogue", "🗂️", "Catalogue"], ["/finance", "💰", "Finance"], ["/settings", "⚙️", "Settings & SLA"]] },
];

type Me = { full_name: string; phone: string; roles: string[] };

export default function PanelLayout({ children }: { children: ReactNode }) {
  const path = usePathname();
  const [me, setMe] = useState<Me | null>(null);

  useEffect(() => {
    api<Me>("auth/me").then(setMe).catch(() => (window.location.href = "/login"));
  }, []);

  const logout = async () => {
    await fetch("/api/auth/logout", { method: "POST" });
    window.location.href = "/login";
  };

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-logo">B</div>
          <div>
            <div className="brand-name">BUZZER</div>
            <div className="brand-sub">Admin Panel</div>
          </div>
        </div>
        <nav className="nav" aria-label="Main">
          {NAV.map((g) => (
            <div key={g.section}>
              <div className="nav-section">{g.section}</div>
              {g.items.map(([href, icon, label]) => {
                const active = href === "/" ? path === "/" : path.startsWith(href);
                return (
                  <Link key={href} href={href} className={active ? "active" : ""} aria-current={active ? "page" : undefined} title={label} aria-label={label}>
                    <span aria-hidden className="nav-icon">{icon}</span> <span className="nav-label">{label}</span>
                  </Link>
                );
              })}
            </div>
          ))}
        </nav>
        <div className="sidebar-user" style={{ marginTop: 24, padding: "0 12px", fontSize: 12, color: "#cfebd9" }}>
          {me ? (
            <>
              <div style={{ fontWeight: 700, color: "#fff" }}>{me.full_name || me.phone}</div>
              <div>{me.roles.join(", ")}</div>
              <button className="btn btn-secondary btn-sm" style={{ marginTop: 10 }} onClick={logout}>Log out</button>
            </>
          ) : "…"}
        </div>
      </aside>
      <main className="main">{me ? children : <div className="muted">Checking session…</div>}</main>
    </div>
  );
}
