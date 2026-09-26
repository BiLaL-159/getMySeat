import { Outlet } from 'react-router'
import Layout from './Layout.tsx'

// The public part of the app (Discovery): the same header as the signed-in app, but pages render
// for anyone. A signed-in visitor still gets their nav; a signed-out one can choose to sign in.
function PublicShell() {
  return (
    <Layout offerSignIn>
      <Outlet />
    </Layout>
  )
}

export default PublicShell
