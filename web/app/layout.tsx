import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'GPS Tracker',
  description: 'Real-time GPS tracking application',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
