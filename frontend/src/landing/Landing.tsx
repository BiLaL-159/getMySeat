import { useEffect } from 'react'
import { mountLanding } from './mountLanding.ts'
import './landing.css'

function Landing() {
  useEffect(() => mountLanding(), [])

  return (
    <>
      <div className="hero" id="top">
        <div className="scene" id="scene">
          <canvas id="heroGL" aria-hidden="true"></canvas>
          <div className="in">
            <nav>
              <a className="logo" href="#top" aria-label="getMySeat home">
                <svg viewBox="0 0 26 26" aria-hidden="true"><path d="M4 11a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4v7H4z" fill="#CDEB3A" /><rect x="2" y="17" width="22" height="4" rx="1.5" fill="#CDEB3A" /><rect x="5" y="21" width="3" height="3" fill="#CDEB3A" /><rect x="18" y="21" width="3" height="3" fill="#CDEB3A" /></svg>
                getMySeat
              </a>
              <div className="navlinks">
                <a href="#view">Your view</a>
                <a href="#guide">What&apos;s on</a>
                <a href="#host">For venues</a>
                <a className="signin" href="#guide">Sign in</a>
              </div>
            </nav>
            <span className="live label"><i></i>Live now · Harbourline Arena, Mumbai</span>
            <div className="copy">
              <h1>Be in<span className="l2">the room.</span></h1>
              <div className="hero-row">
                <p>Pick your exact seat, see the stage from it, and book the whole group side by side.</p>
                <form className="stub" id="search" role="search">
                  <label htmlFor="q-city"><span className="label">City</span>
                    <select id="q-city"><option>Mumbai</option><option>Bengaluru</option><option>Delhi</option><option>Pune</option></select>
                  </label>
                  <label htmlFor="q-what"><span className="label">Who or what</span>
                    <input id="q-what" type="text" placeholder="An artist, a comic, a team" autoComplete="off" />
                  </label>
                  <label htmlFor="q-when"><span className="label">When</span>
                    <select id="q-when"><option>This weekend</option><option>Next 30 days</option><option>Any time</option></select>
                  </label>
                  <button type="submit">Find seats</button>
                </form>
              </div>
            </div>
          </div>
        </div>
        <div className="in">
        </div>
        <div className="tonight">
          <div className="in">
            <span className="label" id="tonightLabel">Tonight in Mumbai</span>
            <ul id="tonightList"></ul>
          </div>
        </div>
      </div>

      <section className="demo" id="view">
        <div className="in">
          <div className="demo-head">
            <h2>Tap a seat.<br /><em>It&apos;s yours.</em></h2>
            <div className="aside">
              <p>This is the real room. <b>Dots are gone, outlines are open</b>, and the price on the ring is the price you pay.</p>
              <a className="showchip" href="#guide"><img src="/assets-v9/p-monsoon.webp" alt="" /><span><b>Monsoon Frequencies</b><span className="label">Harbourline Arena · Sat 17 Oct · 7:30 pm</span></span></a>
            </div>
          </div>
          <div className="house">
            <div className="floor">
              <div className="floor-bar">
                <div className="party" role="group" aria-label="How many of you are going">
                  <span className="label">Party of</span>
                  <div className="pips" id="pips"></div>
                  <b className="pn num" id="pn" aria-hidden="true">2</b>
                </div>
                <button className="best" id="best" type="button"><svg viewBox="0 0 14 14" aria-hidden="true"><path d="M7 0l1.6 5.4L14 7l-5.4 1.6L7 14l-1.6-5.4L0 7l5.4-1.6z" fill="currentColor" /></svg><span id="bestTxt">Best 2 together</span></button>
              </div>
              <div className="plan" id="plan"></div>
              <div className="floor-foot label">
                <div className="legend"><span><i></i>Open</span><span><i className="g"></i>Gone</span><span><i className="m"></i>Yours</span><span><i className="l"></i>Your sightline</span></div>
                <span className="drag">Drag to look around</span>
                <span className="keys">Arrow keys work too</span>
              </div>
            </div>
            <aside className="rail" aria-label="Your ticket">
              <span className="label"><span>Your ticket</span><span id="tkLeft"></span></span>
              <div className="tkt">
                <div className="tkt-ev">
                  <span className="label" id="tkAdmit">Admit 2</span>
                  <b>Monsoon Frequencies</b>
                  <span className="label">Harbourline Arena · Gate 3</span>
                </div>
                <dl className="tkt-seat">
                  <div><dt className="label">Row</dt><dd id="tkRow">B</dd></div>
                  <div><dt className="label">Seats</dt><dd id="tkSeats" className="num">7–8</dd></div>
                  <div><dt className="label">Block</dt><dd id="tkBlock" className="sm">Centre</dd></div>
                </dl>
                <ul className="tkt-facts">
                  <li><span className="label">View</span><span><span id="tkView">Dead centre</span><svg className="gauge" viewBox="0 0 34 19" aria-hidden="true"><path d="M2 17a15 15 0 0 1 30 0" /><line id="gaugeN" x1="17" y1="17" x2="17" y2="4" /></svg></span></li>
                  <li><span className="label">To the stage</span><span id="tkDist" className="num">10 m</span></li>
                  <li><span className="label">Price</span><span id="tkEach" className="num">2 × ₹6,500</span></li>
                </ul>
                <div className="tkt-total">
                  <span><span className="label">Total</span><small>No fees are added at checkout.</small></span>
                  <b id="tkTotal" className="num">₹13,000</b>
                </div>
                <a className="tkt-book" href="#guide"><i></i><span>Book these seats</span><span className="arr" aria-hidden="true">→</span><i></i></a>
              </div>
              <p className="msg" id="msg" aria-live="polite"></p>
            </aside>
          </div>
          <div className="promises">
            <p><span className="label">01</span><b>Sit together</b>Tell us how many are going and we only offer seats side by side.</p>
            <p><span className="label">02</span><b>Your seat is yours</b>The seat on your ticket is the one you tapped. No doubles at the door.</p>
            <p><span className="label">03</span><b>One price</b>The number on the map is the number you pay. No fees added at checkout.</p>
          </div>
        </div>
      </section>

      <section className="guide" id="guide">
        <div className="in">
          <div className="guide-head">
            <h2>What&apos;s on</h2>
            <div className="tabs label" role="group" aria-label="Filter by type">
              <button aria-pressed="true" data-cat="ALL">All</button>
              <button aria-pressed="false" data-cat="MUSIC">Music</button>
              <button aria-pressed="false" data-cat="COMEDY">Comedy</button>
              <button aria-pressed="false" data-cat="THEATRE">Theatre</button>
              <button aria-pressed="false" data-cat="SPORTS">Sport</button>
            </div>
          </div>
          <div id="gigs"></div>
        </div>
      </section>

      <section className="host" id="host">
        <div className="in host-grid">
          <div>
            <span className="label kicker">For venues and promoters</span>
            <h2>Draw your room once.</h2>
            <p>Comedy cellars, concert halls, stadiums. Lay out the floor, price it by section, and every fan can choose a spot and see the view before they pay.</p>
            <ol className="steps">
              <li><b>Draw</b><span>Rows, seats, aisles, or a standing Fan Pit.</span></li>
              <li><b>Price</b><span>A price per section, front row to back.</span></li>
              <li><b>Publish</b><span>Add dates and go on sale the same day.</span></li>
            </ol>
            <a className="outline" href="#host">Set up your venue <span aria-hidden="true">→</span></a>
          </div>
          <div className="editor" aria-hidden="true">
            <div className="ed-bar label"><div className="tools"><span className="on" id="t0">Draw</span><span id="t1">Price</span><span id="t2">Publish</span></div><span>Chalk Room · 212 seats</span></div>
            <canvas id="editor"></canvas>
          </div>
        </div>
      </section>

      <section className="final">
        <div className="in">
          <div className="final-copy">
            <h2>Your seat<br />is waiting.</h2>
            <a className="ticket" href="#guide"><span>Find a show</span><span aria-hidden="true">→</span></a>
          </div>
          <div className="t3d" aria-hidden="true">
            <div className="tk" id="tk">
              <div className="tk-art"></div>
              <div className="tk-body">
                <span className="label">Admit 4 · Sat 17 Oct</span>
                <b>Monsoon Frequencies</b>
                <span className="label">Harbourline Arena</span>
                <div className="tk-seat"><span><small className="label">Row</small>E</span><span><small className="label">Seats</small>9–12</span><span><small className="label">Gate</small>3</span></div>
              </div>
              <i className="foil"></i><i className="shine"></i>
            </div>
          </div>
          <small className="label">Mumbai · Bengaluru · Delhi · Pune · more cities soon</small>
        </div>
      </section>

      <footer>
        <div className="in">
          <div><p className="big">getMySeat</p><p style={{ margin: 0, color: 'var(--night-dim)', maxWidth: '30ch' }}>Live shows across India, booked seat by seat.</p></div>
          <div><h4 className="label">Cities</h4><ul><li><a href="#guide">Mumbai</a></li><li><a href="#guide">Bengaluru</a></li><li><a href="#guide">Delhi</a></li><li><a href="#guide">Pune</a></li></ul></div>
          <div><h4 className="label">Shows</h4><ul><li><a href="#guide">Music</a></li><li><a href="#guide">Comedy</a></li><li><a href="#guide">Theatre</a></li><li><a href="#guide">Sport</a></li></ul></div>
          <div><h4 className="label">Venues</h4><ul><li><a href="#host">List your venue</a></li><li><a href="#host">Seat map editor</a></li><li><a href="#host">Help</a></li></ul></div>
          <div className="legal label"><span>© 2026 getMySeat</span><span>Terms · Privacy · Refunds</span></div>
        </div>
      </footer>
      <canvas id="posterGL" aria-hidden="true"></canvas>
    </>
  )
}

export default Landing
